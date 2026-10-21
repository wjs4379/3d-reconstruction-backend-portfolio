#!/usr/bin/env python3
"""Private backup of metaround_estate_v5 only, without stopping any service.

Run after Flyway/readiness. --single-transaction snapshots InnoDB data, not
concurrent DDL. --skip-triggers/--skip-routines/--skip-events are deliberate:
this schema has no such objects and its app user receives no TRIGGER privilege.
Immutable S3 assets are not recopied; their SQL-recorded versions must remain.
The runtime role needs PutObject ONLY on backups/estate/*; an administrator
separately verifies/downloads the recorded versions for a restricted restore.
No cloud API except three conditional PutObject requests is used by this job.
Only the same-host cloudfront-local deployment is supported. Direct TLS/RDS
spatial_asset_db profiles must use a separate, reviewed backup configuration.
"""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import secrets
import stat
import subprocess
from urllib.parse import parse_qs, urlsplit

SCHEMA = "metaround_estate_v5"
MYSQL_CONTAINER = "gp-backend-mysql-1"
DEFAULT_ENV = Path("/etc/metaround/aws-cloudfront-local.preview.env")
DEFAULT_ROOT = Path("/var/lib/metaround-estate-backups")
REGION = "ap-northeast-2"
# Official AWS CLI 2.38.0, immutable image used and verified on the EC2 host.
AWS_IMAGE = "amazon/aws-cli:2.38.0@sha256:ed3233c28ad56d6a66e9e7623a495ab2d7eb08d425f8eea0fdbdfacb12de2ba8"
DOCKER = ["/usr/bin/docker", "--host", "unix:///var/run/docker.sock"]
MAX_ENV_BYTES = 65536
MAX_SQL_BYTES = 512 * 1024 * 1024
RUN_ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
           "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
           "DOCKER_CONFIG": "/nonexistent-metaround-backup-docker-config"}
DUMP_SCRIPT = '''IFS= read -r MYSQL_PWD || exit 70
export MYSQL_PWD
exec mysqldump --protocol=TCP --host=127.0.0.1 --port=3306 --user="$1" \
  --ssl-mode=VERIFY_CA --ssl-ca=/var/lib/mysql/ca.pem --single-transaction \
  --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --skip-triggers \
  --skip-routines --skip-events --skip-add-locks --skip-comments \
  --default-character-set=utf8mb4 metaround_estate_v5
'''


class BackupError(Exception):
    def __init__(self, stage, code=65, confirmed_object=None):
        self.stage, self.code = stage, code
        self.confirmed_object = confirmed_object
        super().__init__(stage)


def no_symlinks(path):
    if not path.is_absolute() or ".." in path.parts or any(char in str(path) for char in ("\n", "\r", ",")):
        raise BackupError("path")
    for part in (path, *path.parents):
        if part.is_symlink():
            raise BackupError("path")


def private_directory(path):
    no_symlinks(path)
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.geteuid():
        raise BackupError("path")


def read_private_env(path):
    no_symlinks(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
            raise BackupError("env-file")
        raw = source.read(MAX_ENV_BYTES + 1)
        if not raw or len(raw) > MAX_ENV_BYTES:
            raise BackupError("env-file")
    # Reuse the existing literal parser, but parse this exact fd snapshot instead
    # of reopening a pathname that might have changed between reads.
    spec = importlib.util.spec_from_file_location("estate_backup_env", Path(__file__).with_name("check-aws-env.py"))
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)

    class Snapshot:
        def read_text(self, encoding="utf-8"):
            return raw.decode(encoding)

    try:
        env = parser.read_env(Snapshot())
    except (ValueError, UnicodeError):
        raise BackupError("env-parse") from None
    username = env.get("DB_USERNAME", "")
    if (not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,31}", username) or
            username.casefold() in {"root", "admin", "master", "rdsadmin", "mysql"}):
        raise BackupError("db-user")
    password = env.get("DB_PASSWORD", "")
    if not password or len(password) > 4096 or any(char in password for char in ("\x00", "\n", "\r")):
        raise BackupError("db-password")
    jdbc = env.get("DB_URL", "")
    if not jdbc.startswith("jdbc:mysql://"):
        raise BackupError("db-schema")
    try:
        url = urlsplit(jdbc[5:])
        query = parse_qs(url.query, keep_blank_values=True, strict_parsing=True)
        correct = (url.hostname == "mysql" and url.port in (None, 3306) and
                   url.path == "/" + SCHEMA and not url.username and not url.password and not url.fragment and
                   query.get("sslMode") == ["VERIFY_CA"])
    except ValueError:
        correct = False
    if not correct:
        raise BackupError("db-schema")
    bucket = env.get("MEDIA_S3_BUCKET", "")
    match = re.fullmatch(r"metaround-estate-media-([0-9]{12})-ap-northeast-2", bucket)
    if not match or env.get("AWS_REGION") != REGION:
        raise BackupError("s3-target")
    return raw, env, match.group(1)


def write_private(path, content):
    no_symlinks(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as target:
        target.write(content)
        target.flush()
        os.fsync(target.fileno())


def replace_state(directory, state):
    temporary = directory / (".state-" + secrets.token_hex(8))
    write_private(temporary, (json.dumps(state, indent=2) + "\n").encode())
    target = directory / "backup-result.json"
    no_symlinks(target)
    os.replace(temporary, target)
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def record_failure(directory, state, stage, code):
    # A storage failure must not replace the original stage or leak exception
    # details. Keep any confirmed S3 version receipts even after a timeout.
    if directory is not None:
        try:
            replace_state(directory, {**state, "status": "failed", "schema": SCHEMA,
                                      "stage": stage, "returncode": code})
        except Exception:
            pass  # Disk/full/path failures may prevent a durable failure record.


@contextmanager
def lock(directory):
    filename = directory / ".backup.lock"
    no_symlinks(filename)
    fd = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
            raise BackupError("lock")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise BackupError("lock", 75) from None
        yield
    finally:
        os.close(fd)


def cap_dump_file():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_SQL_BYTES, MAX_SQL_BYTES))


def run_command(command, *, data=None, destination=None, timeout=300, file_limit=False):
    return subprocess.run(command, input=data, stdout=destination if destination is not None else subprocess.PIPE,
                          stderr=subprocess.DEVNULL, env=RUN_ENV, timeout=timeout, check=False,
                          preexec_fn=cap_dump_file if file_limit else None)


def file_info(path):
    no_symlinks(path)
    digest = hashlib.sha256()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or
                info.st_uid != os.geteuid()):
            raise BackupError("file-integrity")
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
        after = os.fstat(fd)
        if (after.st_size, after.st_mtime_ns) != (info.st_size, info.st_mtime_ns):
            raise BackupError("file-integrity")
    return {"bytes": info.st_size, "sha256": digest.hexdigest(),
            "checksumSHA256": base64.b64encode(digest.digest()).decode("ascii")}


def upload(directory, filename, bucket, account, prefix, runner, stage):
    before = file_info(directory / filename)
    command = [*DOCKER, "run", "--rm", "--pull=never", "--network", "host", "--read-only",
               "--tmpfs", "/tmp:rw,nosuid,nodev,size=32m", "--mount",
               f"type=bind,source={directory},target=/backup,readonly",
               "--env", "AWS_EC2_METADATA_DISABLED=false", "--env", "AWS_MAX_ATTEMPTS=3",
               AWS_IMAGE, "s3api", "put-object", "--region", REGION, "--bucket", bucket,
               "--expected-bucket-owner", account, "--key", prefix + filename,
               "--body", "/backup/" + filename, "--content-type", "application/octet-stream",
               "--server-side-encryption", "AES256", "--cache-control", "no-store",
               "--if-none-match", "*", "--checksum-algorithm", "SHA256",
               "--checksum-sha256", before["checksumSHA256"], "--metadata", "sha256=" + before["sha256"],
               "--cli-connect-timeout", "10", "--cli-read-timeout", "60", "--no-cli-pager", "--output", "json"]
    result = runner(command)
    if result.returncode:
        raise BackupError(stage, result.returncode)
    try:
        response = json.loads(result.stdout)
    except (ValueError, TypeError):
        raise BackupError(stage) from None
    if not isinstance(response, dict):
        raise BackupError(stage)
    version = response.get("VersionId")
    if (response.get("ChecksumSHA256") != before["checksumSHA256"] or
            response.get("ServerSideEncryption") != "AES256" or not isinstance(version, str) or
            not version or version == "null" or len(version) > 2048 or any(ord(c) < 32 for c in version)):
        raise BackupError(stage)
    receipt = {**before, "key": prefix + filename, "versionId": version, "serverSideEncryption": "AES256"}
    # Native S3 checksum/version/SSE already confirm these exact remote bytes.
    # Retain that receipt even if the local file disappears or changes afterward;
    # the run still fails and cannot publish the next object or mark completion.
    try:
        after = file_info(directory / filename)
    except BackupError as error:
        error.confirmed_object = (filename, receipt)
        raise
    except Exception:
        raise BackupError("file-integrity", confirmed_object=(filename, receipt)) from None
    if after != before:
        raise BackupError("file-integrity", confirmed_object=(filename, receipt))
    return receipt


def perform_backup(env_file=DEFAULT_ENV, backup_root=DEFAULT_ROOT, runner=run_command, now=None):
    raw, env, account = read_private_env(Path(env_file))
    backup_root = Path(backup_root)
    private_directory(backup_root)
    directory = None
    state = {"files": {}}
    with lock(backup_root):
        try:
            now = now or datetime.now(timezone.utc)
            if now.tzinfo is None:
                raise BackupError("timestamp")
            now = now.astimezone(timezone.utc)
            stamp = now.strftime("%Y%m%dT%H%M%S%fZ") + "-" + secrets.token_hex(8)
            directory = backup_root / stamp
            directory.mkdir(mode=0o700, exist_ok=False)
            prefix = "backups/estate/" + stamp + "/"
            state = {"status": "started", "schema": SCHEMA, "createdAtUtc": now.isoformat(), "files": {}}
            replace_state(directory, state)
            sql = directory / "estate.sql"
            fd = os.open(sql, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "wb") as output:
                command = [*DOCKER, "exec", "-i", MYSQL_CONTAINER, "/bin/sh", "-c", DUMP_SCRIPT,
                           "estate-backup", env["DB_USERNAME"]]
                result = runner(command, data=(env["DB_PASSWORD"] + "\n").encode(), destination=output,
                                timeout=900, file_limit=True)
                output.flush()
                os.fsync(output.fileno())
            if result.returncode:
                raise BackupError("dump", result.returncode)
            if not 0 < sql.stat().st_size <= MAX_SQL_BYTES:
                raise BackupError("dump")
            write_private(directory / "aws.env", raw)
            receipts = {}
            for filename, stage in (("estate.sql", "upload-sql"), ("aws.env", "upload-env")):
                receipts[filename] = upload(directory, filename, env["MEDIA_S3_BUCKET"], account, prefix, runner, stage)
                state["files"] = dict(receipts)
                replace_state(directory, state)
            # Publish the manifest last. Its own native checksum/version receipt
            # cannot be embedded into itself; record that in local backup-result.
            manifest = {"format": 1, "schema": SCHEMA, "createdAtUtc": now.isoformat(),
                        "dataFilesVerified": True, "files": receipts,
                        "mysqlDump": {"container": MYSQL_CONTAINER, "singleTransaction": True,
                                      "sslMode": "VERIFY_CA", "triggersRoutinesEventsIncluded": False},
                        "mediaContract": "SQL-referenced immutable assets versions must be retained; no asset version expiry",
                        "restore": "Administrator retrieves exact versions; schema-only TLS restore; never import into original schema"}
            write_private(directory / "manifest.json", (json.dumps(manifest, indent=2) + "\n").encode())
            receipts["manifest.json"] = upload(directory, "manifest.json", env["MEDIA_S3_BUCKET"], account,
                                                prefix, runner, "upload-manifest")
            state.update(status="complete", files=receipts, completeAtUtc=datetime.now(timezone.utc).isoformat())
            replace_state(directory, state)
            return state
        except BackupError as error:
            if error.confirmed_object is not None:
                filename, receipt = error.confirmed_object
                state["files"] = {**state.get("files", {}), filename: receipt}
            record_failure(directory, state, error.stage, error.code)
            raise
        except subprocess.TimeoutExpired:
            record_failure(directory, state, "timeout", 75)
            raise BackupError("timeout", 75) from None
        except Exception:
            record_failure(directory, state, "storage", 73)
            raise BackupError("storage", 73) from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--backup-root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args(argv)
    try:
        perform_backup(args.env_file, args.backup_root)
    except BackupError as error:
        print(f"estate-backup stage={error.stage} returncode={error.code}")
        return 1
    except Exception:
        print("estate-backup stage=validation returncode=65")
        return 1
    print("estate-backup stage=complete returncode=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
