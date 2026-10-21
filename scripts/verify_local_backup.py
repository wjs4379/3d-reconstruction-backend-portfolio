#!/usr/bin/env python3
"""Restore a trusted private backup into a NEW schema; keep original DB/media/backup unchanged."""
import argparse
import hashlib
import json
import os
import re
import secrets
import stat
import subprocess
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from local_demo import configuration, mysql_query, executable, DEFAULT_RUNTIME, private_json, mysql_command, mysql_row_digest
from backup_database_fingerprint import database_fingerprint, literal_grant_identifier, literal_grant_pattern, require_same_fingerprint


def private_directory(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError('Backup ancestors must be owner-only directories, without symbolic links')


def raw_absolute(path):
    path = Path(path).expanduser()
    if '..' in path.parts:
        raise ValueError('Parent traversal is not a trusted backup path')
    return path if path.is_absolute() else Path.cwd() / path


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError('Backup files and manifest must be owner-only regular files')
    return info


def open_private(path):
    before = private_file(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        actual = os.fstat(fd)
        if (actual.st_dev, actual.st_ino, actual.st_size, actual.st_mtime_ns) != \
                (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns):
            raise ValueError('Backup file changed during validation')
        return os.fdopen(fd, 'rb')
    except Exception:
        os.close(fd)
        raise


def stream_sha(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def validate_archive(path):
    with open_private(path) as source, tarfile.open(fileobj=source, mode='r:gz') as archive:
        names = set()
        for member in archive:
            name = PurePosixPath(member.name)
            if (name.is_absolute() or '..' in name.parts or not name.parts or name.parts[0] != 'media'
                    or member.name in names or not (member.isfile() or member.isdir())):
                raise ValueError('Media archive contains an unsafe path, duplicate, link or special file')
            names.add(member.name)


def validate_backup(runtime, directory):
    runtime, directory = raw_absolute(runtime), raw_absolute(directory)
    private_directory(runtime)
    try:
        relative = directory.relative_to(runtime / 'backups')
    except ValueError:
        raise ValueError('Only a trusted private backup under this runtime/backups is accepted') from None
    if not relative.parts:
        raise ValueError('Select one backup, not the backups parent')
    current = runtime / 'backups'
    private_directory(current)
    for part in relative.parts:
        current /= part
        private_directory(current)
    manifest_path = directory / 'backup-manifest.json'
    if private_file(manifest_path).st_size > 1024 * 1024:
        raise ValueError('Backup manifest exceeds the allowed size')
    with open_private(manifest_path) as stream:
        manifest = json.load(stream)
    if (not isinstance(manifest, dict) or type(manifest.get('version')) is not int or manifest['version'] != 1
            or manifest.get('database') != 'gp_estate_demo' or not isinstance(manifest.get('files'), dict)):
        raise ValueError('Unexpected backup manifest/schema')
    files = manifest['files']
    if not {'backend.sql', 'media.tar.gz', 'local-secrets.json'}.issubset(files):
        raise ValueError('Backend/media/secret backup is incomplete')
    for name, info in files.items():
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or name in ('.', '..'):
            raise ValueError('Unexpected manifest path')
        if (not isinstance(info, dict) or type(info.get('bytes')) is not int or info['bytes'] < 0
                or not isinstance(info.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', info['sha256'])):
            raise ValueError('Malformed manifest size/checksum')
        path = directory / name
        if private_file(path).st_size != info['bytes']:
            raise ValueError('Backup checksum size mismatch; refused before DB access')
        with open_private(path) as stream:
            if stream_sha(stream) != info['sha256']:
                raise ValueError('Backup checksum mismatch; refused before DB access')
    baseline = manifest.get('databaseFingerprint')
    if baseline is not None:
        if (not isinstance(baseline, dict) or baseline.get('format') != 1 or not isinstance(baseline.get('tables'), list)
                or baseline.get('tableCount') != len(baseline['tables'])):
            raise ValueError('Malformed optional database fingerprint')
    validate_archive(directory / 'media.tar.gz')
    return runtime, directory, manifest


def report_destination(runtime, path, suffix):
    target = raw_absolute(path) if path else runtime / 'restore-verifications' / (suffix + '.json')
    try:
        parts = target.parent.relative_to(runtime).parts
    except ValueError:
        raise ValueError('Restore reports must remain in this private runtime') from None
    if target.is_relative_to(runtime / 'backups'):
        raise ValueError('Do not modify an original backup with a restore report')
    current = runtime
    for part in parts:
        current /= part
        if not current.exists() and not current.is_symlink():
            current.mkdir(mode=0o700)
        private_directory(current)
    if target.exists() or target.is_symlink():
        raise ValueError('Restore report must be a new private path')
    return target


def verify_backup(runtime, config, mysql_home, directory, *, compare_active=False, report_path=None):
    runtime, directory, manifest = validate_backup(runtime, directory)
    suffix = secrets.token_hex(6)
    schema, username, password = 'gp_estate_restore_' + suffix, 'gp_restore_' + suffix, secrets.token_hex(32)
    report = report_destination(runtime, report_path, suffix)
    partial = mysql_query(runtime, config, mysql_home, 'SELECT @@GLOBAL.partial_revokes;', root=True)
    grant = literal_grant_identifier(schema, partial)
    if mysql_query(runtime, config, mysql_home, f"SELECT COUNT(*) FROM mysql.user WHERE User='{username}' AND Host='127.0.0.1';", root=True) != '0':
        raise ValueError('Temporary restore account name already exists')
    account_attempted = False
    output = None
    # Credentials and temporary clients are outside the immutable original backup.
    with tempfile.TemporaryDirectory(prefix='restore-client-', dir=runtime) as scratch:
        client_path = Path(scratch) / 'client.cnf'
        private_json(Path(scratch) / 'context.json', {'restoreSchema': schema, 'sourceBackup': str(directory)})
        try:
            mysql_query(runtime, config, mysql_home, f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4;', root=True)
            account_attempted = True
            mysql_query(runtime, config, mysql_home, f"CREATE USER '{username}'@'127.0.0.1' IDENTIFIED BY '{password}';", root=True)
            mysql_query(runtime, config, mysql_home, f"GRANT ALL PRIVILEGES ON {grant}.* TO '{username}'@'127.0.0.1';", root=True)
            actual_pattern = mysql_query(runtime, config, mysql_home, f"SELECT HEX(Db) FROM mysql.db WHERE User='{username}' AND Host='127.0.0.1';", root=True)
            if actual_pattern != literal_grant_pattern(schema, partial).encode().hex().upper():
                raise RuntimeError('Temporary account grant is not restricted to the exact schema')
            fd = os.open(client_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'w') as stream:
                stream.write(f'[client]\nhost=127.0.0.1\nprotocol=tcp\nport={config["mysqlPort"]}\nuser={username}\npassword={password}\n')
            command = [executable(mysql_home, 'mysql'), '--defaults-file=' + str(client_path), '--database=' + schema,
                       '--batch', '--skip-column-names', '--binary-mode=1', '--local-infile=0', '--default-character-set=utf8mb4']
            with open_private(directory / 'backend.sql') as sql:
                result = subprocess.run(command, stdin=sql, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            if result.returncode:
                raise RuntimeError('Restore SQL failed; isolated schema retained; no root import used')
            def query(text):
                result = subprocess.run(command, input=text, text=True, capture_output=True)
                if result.returncode:
                    raise RuntimeError('Restored DB verification failed; details withheld')
                return result.stdout.strip()
            restored = database_fingerprint(query, schema, lambda sql: mysql_row_digest(command, sql))
            snapshot = manifest.get('databaseFingerprint')
            if snapshot is not None:
                require_same_fingerprint(snapshot, restored)
            active_compared = False
            if compare_active:
                active = database_fingerprint(lambda sql: mysql_query(runtime, config, mysql_home, sql), config['database'],
                    lambda sql: mysql_row_digest(mysql_command(runtime, config, mysql_home) + ['--batch', '--skip-column-names', '--default-character-set=utf8mb4'], sql))
                require_same_fingerprint(active, restored)
                active_compared = True
            media_rows = query("SELECT object_key,checksum,size_bytes FROM media_assets WHERE status='VERIFIED';")
            verified = 0
            with open_private(directory / 'media.tar.gz') as source, tarfile.open(fileobj=source, mode='r:gz') as archive:
                for line in media_rows.splitlines():
                    object_key, checksum, size = line.split('\t')
                    key = PurePosixPath(object_key)
                    if key.is_absolute() or '..' in key.parts or not key.parts or '\\' in object_key:
                        raise ValueError('Unexpected persisted media key')
                    try:
                        member = archive.getmember('media/' + object_key)
                    except KeyError:
                        raise RuntimeError('Restored DB references absent backup media') from None
                    if not member.isfile() or member.size != int(size):
                        raise RuntimeError('Media backup size/type mismatch')
                    with archive.extractfile(member) as stream:
                        digest = stream_sha(stream)
                    if checksum != 'NULL' and digest != checksum:
                        raise RuntimeError('Media backup SHA256 mismatch')
                    verified += 1
            output = {'success': True, 'restoreSchema': schema, 'rowCounts': {item['table']: item['rows'] for item in restored['tables']},
                      'databaseFingerprint': restored, 'snapshotFingerprintCompared': snapshot is not None,
                      'snapshotFingerprintStatus': 'matched' if snapshot is not None else 'not-present-in-legacy-v1-manifest',
                      'activeDatabaseComparisonRequested': compare_active, 'activeDatabaseFingerprintMatched': active_compared,
                      'verifiedMediaFiles': verified, 'activeDatabaseChanged': False, 'activeMediaChanged': False,
                      'originalBackupChanged': False, 'temporaryAccountRemoved': False, 'reportPath': str(report),
                      'note': 'All restored base tables fingerprinted; all foreign keys stay in this schema with zero orphans. Trusted owner-local checksums are not a cryptographic signature.'}
        finally:
            client_path.unlink(missing_ok=True)
            if account_attempted:
                mysql_query(runtime, config, mysql_home, f"DROP USER IF EXISTS '{username}'@'127.0.0.1';", root=True)
    output['temporaryAccountRemoved'] = True
    private_json(report, output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, default=DEFAULT_RUNTIME)
    parser.add_argument('--mysql-home', default='/usr/local/mysql')
    parser.add_argument('--compare-active', action='store_true', help='Explicitly compare current active DB too; historical snapshots can legitimately differ')
    parser.add_argument('--report-path', type=Path, help='NEW private report path under runtime, outside backups')
    args = parser.parse_args()
    try:
        runtime = raw_absolute(args.runtime)
        private_directory(runtime)
        private_file(runtime / 'local-secrets.json')
        runtime, config = configuration(runtime)
        print(json.dumps(verify_backup(runtime, config, args.mysql_home, args.backup,
            compare_active=args.compare_active, report_path=args.report_path), ensure_ascii=False, indent=2))
    except Exception as error:
        parser.exit(1, 'Backup verification refused: ' + type(error).__name__ + '; private data and credentials withheld\n')


if __name__ == '__main__':
    main()
