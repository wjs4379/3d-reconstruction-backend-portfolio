import base64
import contextlib
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("estate_backup", DEPLOY / "backup-estate.py")
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
PASSWORD = "fake-unit-password-do-not-log"
PRIVATE_SQL = b"CREATE TABLE sample(id INT); INSERT INTO sample VALUES (1); -- private-row-marker\n"
BUCKET = "metaround-estate-media-123456789012-ap-northeast-2"


class FakeDocker:
    """Independent fake storage computes the checksum from mounted file bytes."""
    def __init__(self, fail_dump=False, receipt_failure=None, mutate_sql=False, mutate_filename=None):
        self.calls = []
        self.uploads = []
        self.fail_dump = fail_dump
        self.receipt_failure = receipt_failure
        self.mutate_filename = "estate.sql" if mutate_sql else mutate_filename

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if "exec" in command:
            kwargs["destination"].write(PRIVATE_SQL)
            return SimpleNamespace(returncode=23 if self.fail_dump else 0, stdout=None)
        mount = command[command.index("--mount") + 1]
        directory = Path(re.search(r"source=([^,]+)", mount).group(1))
        filename = Path(command[command.index("--body") + 1]).name
        raw = (directory / filename).read_bytes()
        key = command[command.index("--key") + 1]
        checksum = base64.b64encode(hashlib.sha256(raw).digest()).decode()
        response = {"ChecksumSHA256": checksum, "ServerSideEncryption": "AES256",
                    "VersionId": "fake-version-" + str(len(self.uploads))}
        self.uploads.append((key, raw))
        if self.receipt_failure and self.receipt_failure[0] == filename:
            response[self.receipt_failure[1]] = self.receipt_failure[2]
        if filename == self.mutate_filename:
            (directory / filename).write_bytes(b"different-after-upload")
        return SimpleNamespace(returncode=0, stdout=json.dumps(response).encode())


class EstateBackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name).resolve()
        self.env = self.base / "preview.env"
        self.root = self.base / "backups"
        self.write_env()

    def tearDown(self):
        self.tmp.cleanup()

    def write_env(self, **changes):
        values = {"DB_URL": "jdbc:mysql://mysql:3306/metaround_estate_v5?sslMode=VERIFY_CA",
                  "DB_USERNAME": "metaround_estate_app", "DB_PASSWORD": PASSWORD,
                  "AWS_REGION": "ap-northeast-2", "MEDIA_S3_BUCKET": BUCKET,
                  "JWT_SECRET": "fake-jwt-marker", "CLOUDFRONT_ORIGIN_SECRET": "fake-origin-marker"}
        values.update(changes)
        raw = "\n".join(key + "=" + value for key, value in values.items()) + "\n"
        self.env.write_text(raw)
        self.env.chmod(0o600)
        return raw.encode()

    def snapshot(self):
        return next(p for p in self.root.iterdir() if p.is_dir())

    def test_success_native_receipts_three_objects_manifest_last_and_private_modes(self):
        fake = FakeDocker()
        original = self.env.read_bytes()
        result = backup.perform_backup(self.env, self.root, fake, datetime(2026, 10, 14, tzinfo=timezone.utc))
        self.assertEqual(result["status"], "complete")
        self.assertEqual([key.rsplit("/", 1)[1] for key, _ in fake.uploads], ["estate.sql", "aws.env", "manifest.json"])
        self.assertTrue(all(key.startswith("backups/estate/") for key, _ in fake.uploads))
        self.assertEqual(fake.uploads[1][1], original)
        manifest = json.loads(fake.uploads[-1][1])
        self.assertEqual(manifest["schema"], "metaround_estate_v5")
        self.assertEqual(set(manifest["files"]), {"estate.sql", "aws.env"})
        self.assertFalse(manifest["mysqlDump"]["triggersRoutinesEventsIncluded"])
        for filename, data in (fake.uploads[0], fake.uploads[1]):
            receipt = manifest["files"][filename.rsplit("/", 1)[1]]
            self.assertEqual(receipt["sha256"], hashlib.sha256(data).hexdigest())
        directory = self.snapshot()
        self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
        self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in directory.iterdir()))

    def test_credentials_stdin_only_and_fixed_schema_tls_trigger_flags(self):
        fake = FakeDocker()
        backup.perform_backup(self.env, self.root, fake)
        command, kwargs = fake.calls[0]
        self.assertEqual(kwargs["data"], (PASSWORD + "\n").encode())
        all_arguments = " ".join(argument for args, _ in fake.calls for argument in args)
        self.assertNotIn(PASSWORD, all_arguments)
        self.assertNotIn("fake-jwt-marker", all_arguments)
        self.assertNotIn("fake-origin-marker", all_arguments)
        self.assertIn("gp-backend-mysql-1", command)
        self.assertIn("--single-transaction", backup.DUMP_SCRIPT)
        for flag in ("--no-tablespaces", "--set-gtid-purged=OFF", "--column-statistics=0", "--skip-triggers", "--ssl-mode=VERIFY_CA"):
            self.assertIn(flag, backup.DUMP_SCRIPT)
        self.assertNotIn("--all-databases", backup.DUMP_SCRIPT)
        self.assertTrue(all("put-object" in args for args, _ in fake.calls[1:]))
        self.assertTrue(all(backup.AWS_IMAGE in args for args, _ in fake.calls[1:]))

    def test_rejects_wrong_schema_host_tls_duplicate_mode_root_user_and_bucket(self):
        cases = [{"DB_URL": "jdbc:mysql://mysql:3306/original_schema?sslMode=VERIFY_CA"},
                 {"DB_URL": "jdbc:mysql://outside:3306/metaround_estate_v5?sslMode=VERIFY_CA"},
                 {"DB_URL": "jdbc:mysql://mysql:3306/metaround_estate_v5?sslMode=DISABLED"},
                 {"DB_URL": "jdbc:mysql://mysql:3306/metaround_estate_v5?sslMode=VERIFY_CA&sslMode=DISABLED"},
                 {"DB_USERNAME": "root"}, {"DB_USERNAME": "user; echo marker"},
                 {"MEDIA_S3_BUCKET": "original-catalog-bucket"}, {"AWS_REGION": "us-east-1"}]
        for case in cases:
            with self.subTest(case=tuple(case)):
                self.write_env(**case)
                fake = FakeDocker()
                with self.assertRaises(backup.BackupError): backup.perform_backup(self.env, self.root, fake)
                self.assertFalse(fake.calls)

    def test_literal_parser_rejects_expansion_escape_duplicates_and_nul(self):
        original = self.env.read_text()
        for suffix in ('EXTRA=$HOME\n', 'EXTRA=path\\value\n', 'DB_USERNAME=second\n', 'EXTRA=value\x00\n'):
            self.env.write_text(original + suffix)
            with self.assertRaises(backup.BackupError): backup.read_private_env(self.env)

    def test_private_env_regular_mode_and_symlink_guards(self):
        self.env.chmod(0o644)
        with self.assertRaises(backup.BackupError): backup.read_private_env(self.env)
        self.env.chmod(0o600)
        link = self.base / "linked.env"
        link.symlink_to(self.env)
        with self.assertRaises(backup.BackupError): backup.read_private_env(link)
        parent = self.base / "linked-parent"
        parent.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(backup.BackupError): backup.read_private_env(parent / self.env.name)
        with self.assertRaises(backup.BackupError): backup.no_symlinks(self.base / ".." / "outside")

    def test_lock_blocks_second_run_without_dump_or_stale_pid(self):
        backup.private_directory(self.root)
        fake = FakeDocker()
        with backup.lock(self.root):
            with self.assertRaises(backup.BackupError) as caught: backup.perform_backup(self.env, self.root, fake)
            self.assertEqual(caught.exception.code, 75)
        self.assertFalse(fake.calls)
        self.assertEqual(backup.perform_backup(self.env, self.root, fake)["status"], "complete")

    def test_dump_failure_never_uploads_or_creates_manifest(self):
        fake = FakeDocker(fail_dump=True)
        with self.assertRaises(backup.BackupError): backup.perform_backup(self.env, self.root, fake)
        self.assertFalse(fake.uploads)
        directory = self.snapshot()
        self.assertFalse((directory / "manifest.json").exists())
        state = json.loads((directory / "backup-result.json").read_text())
        self.assertEqual((state["status"], state["stage"], state["returncode"]), ("failed", "dump", 23))

    def test_checksum_failure_before_manifest_retains_failed_not_complete_state(self):
        fake = FakeDocker(receipt_failure=("aws.env", "ChecksumSHA256", "wrong"))
        with self.assertRaises(backup.BackupError): backup.perform_backup(self.env, self.root, fake)
        self.assertEqual(len(fake.uploads), 2)
        self.assertFalse((self.snapshot() / "manifest.json").exists())
        state = json.loads((self.snapshot() / "backup-result.json").read_text())
        self.assertEqual(state["status"], "failed")
        self.assertEqual(set(state["files"]), {"estate.sql"})

    def test_manifest_missing_version_or_wrong_sse_cannot_mark_success(self):
        for filename, field, bad in (("manifest.json", "VersionId", "null"), ("estate.sql", "ServerSideEncryption", "aws:kms")):
            with self.subTest(field=field):
                root = self.base / field
                fake = FakeDocker(receipt_failure=(filename, field, bad))
                with self.assertRaises(backup.BackupError): backup.perform_backup(self.env, root, fake)
                directory = next(p for p in root.iterdir() if p.is_dir())
                self.assertEqual(json.loads((directory / "backup-result.json").read_text())["status"], "failed")

    def test_timeout_after_sql_keeps_confirmed_version_and_never_publishes_manifest(self):
        fake = FakeDocker()

        def interrupted(command, **kwargs):
            if "put-object" in command and command[command.index("--body") + 1] == "/backup/aws.env":
                raise subprocess.TimeoutExpired(command, 300, stderr=b"private-row-marker")
            return fake(command, **kwargs)

        with self.assertRaises(backup.BackupError) as caught:
            backup.perform_backup(self.env, self.root, interrupted)
        self.assertEqual((caught.exception.stage, caught.exception.code), ("timeout", 75))
        state = json.loads((self.snapshot() / "backup-result.json").read_text())
        self.assertEqual(state["status"], "failed")
        self.assertEqual(set(state["files"]), {"estate.sql"})
        self.assertEqual(len(fake.uploads), 1)
        self.assertFalse((self.snapshot() / "manifest.json").exists())

    def test_malformed_receipt_and_empty_or_excessive_dump_fail_before_completion(self):
        fake = FakeDocker()

        def invalid_json(command, **kwargs):
            result = fake(command, **kwargs)
            if "put-object" in command:
                result.stdout = b"[]"
            return result

        with self.assertRaises(backup.BackupError) as caught:
            backup.perform_backup(self.env, self.root, invalid_json)
        self.assertEqual(caught.exception.stage, "upload-sql")
        def empty_dump(command, **kwargs):
            self.assertIn("exec", command)
            return SimpleNamespace(returncode=0, stdout=None)

        with self.assertRaises(backup.BackupError) as caught:
            backup.perform_backup(self.env, self.base / "empty-dump", empty_dump)
        self.assertEqual(caught.exception.stage, "dump")
        fake = FakeDocker()
        with patch.object(backup, "MAX_SQL_BYTES", len(PRIVATE_SQL) - 1):
            with self.assertRaises(backup.BackupError) as caught:
                backup.perform_backup(self.env, self.base / "oversized-dump", fake)
        self.assertEqual(caught.exception.stage, "dump")
        self.assertFalse(fake.uploads)

    def test_failure_record_cannot_replace_original_error_and_timestamps_are_utc(self):
        fake = FakeDocker(fail_dump=True)
        original = backup.replace_state

        def no_disk_for_failed_state(directory, state):
            if state["status"] == "failed":
                raise OSError("private-value-must-never-be-logged")
            original(directory, state)

        with patch.object(backup, "replace_state", side_effect=no_disk_for_failed_state):
            with self.assertRaises(backup.BackupError) as caught:
                backup.perform_backup(self.env, self.root, fake)
        self.assertEqual((caught.exception.stage, caught.exception.code), ("dump", 23))
        result = backup.perform_backup(self.env, self.root, FakeDocker(),
                                       datetime(2026, 10, 14, 9, tzinfo=timezone(timedelta(hours=9))))
        self.assertEqual(result["createdAtUtc"], "2026-10-14T00:00:00+00:00")

    def test_local_file_changed_after_put_prevents_completion(self):
        fake = FakeDocker(mutate_sql=True)
        with self.assertRaises(backup.BackupError) as caught: backup.perform_backup(self.env, self.root, fake)
        self.assertEqual(caught.exception.stage, "file-integrity")
        self.assertEqual(len(fake.uploads), 1)
        state = json.loads((self.snapshot() / "backup-result.json").read_text())
        self.assertEqual(state["status"], "failed")
        self.assertEqual(set(state["files"]), {"estate.sql"})
        receipt = state["files"]["estate.sql"]
        self.assertEqual(receipt["versionId"], "fake-version-0")
        self.assertEqual(receipt["sha256"], hashlib.sha256(PRIVATE_SQL).hexdigest())
        self.assertFalse((self.snapshot() / "manifest.json").exists())

    def test_local_env_changed_after_put_retains_both_confirmed_receipts_without_manifest(self):
        original = self.env.read_bytes()
        fake = FakeDocker(mutate_filename="aws.env")
        with self.assertRaises(backup.BackupError) as caught:
            backup.perform_backup(self.env, self.root, fake)
        self.assertEqual(caught.exception.stage, "file-integrity")
        state = json.loads((self.snapshot() / "backup-result.json").read_text())
        self.assertEqual(state["status"], "failed")
        self.assertEqual(set(state["files"]), {"estate.sql", "aws.env"})
        self.assertEqual(state["files"]["aws.env"]["versionId"], "fake-version-1")
        self.assertEqual(state["files"]["aws.env"]["sha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(len(fake.uploads), 2)
        self.assertFalse((self.snapshot() / "manifest.json").exists())
        self.assertNotIn(PASSWORD, json.dumps(state))
        self.assertNotIn("fake-jwt-marker", json.dumps(state))

    def test_local_file_removed_after_confirmed_put_keeps_version_and_failed_state(self):
        fake = FakeDocker()

        def deleted_after_put(command, **kwargs):
            response = fake(command, **kwargs)
            if "put-object" in command and command[command.index("--body") + 1] == "/backup/estate.sql":
                mount = command[command.index("--mount") + 1]
                directory = Path(re.search(r"source=([^,]+)", mount).group(1))
                (directory / "estate.sql").unlink()
            return response

        with self.assertRaises(backup.BackupError) as caught:
            backup.perform_backup(self.env, self.root, deleted_after_put)
        self.assertEqual(caught.exception.stage, "file-integrity")
        state = json.loads((self.snapshot() / "backup-result.json").read_text())
        self.assertEqual(state["status"], "failed")
        self.assertEqual(set(state["files"]), {"estate.sql"})
        self.assertEqual(state["files"]["estate.sql"]["versionId"], "fake-version-0")
        self.assertEqual(len(fake.uploads), 1)
        self.assertFalse((self.snapshot() / "manifest.json").exists())

    def test_cli_failure_logs_only_stage_code_not_secret_sql_or_raw_stderr(self):
        fake = FakeDocker(fail_dump=True)
        real = backup.perform_backup
        output, error = io.StringIO(), io.StringIO()
        with patch.object(backup, "perform_backup", side_effect=lambda env, root: real(env, root, fake)):
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
                result = backup.main(["--env-file", str(self.env), "--backup-root", str(self.root)])
        self.assertEqual(result, 1)
        self.assertEqual(error.getvalue(), "")
        self.assertEqual(output.getvalue(), "estate-backup stage=dump returncode=23\n")
        for forbidden in (PASSWORD, "private-row-marker", "fake-jwt-marker", str(self.env)):
            self.assertNotIn(forbidden, output.getvalue())

    def test_runner_discards_stderr_and_inherited_credential_endpoint_environment(self):
        original_home = os.environ.get("HOME")
        with patch.dict(os.environ, {"AWS_SECRET_ACCESS_KEY": "do-not-inherit", "AWS_ENDPOINT_URL": "https://outside", "DOCKER_HOST": "tcp://outside"}):
            with patch.object(backup.subprocess, "run") as run:
                backup.run_command(["public-command"], data=b"fake", file_limit=True)
        kwargs = run.call_args.kwargs
        self.assertEqual(kwargs["stderr"], subprocess.DEVNULL)
        self.assertFalse(any(k.startswith("AWS_") or k == "DOCKER_HOST" for k in kwargs["env"]))
        self.assertFalse(any(k in kwargs["env"] for k in ("HOME", "home", "CODEX_HOME")))
        self.assertEqual(kwargs["env"]["DOCKER_CONFIG"], "/nonexistent-metaround-backup-docker-config")
        self.assertEqual(os.environ.get("HOME"), original_home)
        self.assertIs(kwargs["preexec_fn"], backup.cap_dump_file)

    def test_backup_writer_policy_cannot_read_delete_or_grant_broad_storage_access(self):
        policy = json.loads((DEPLOY / "aws/s3-backup-writer-policy.example.json").read_text())
        statements = policy["Statement"]
        self.assertEqual(1, len(statements))
        statement = statements[0]
        self.assertEqual("Allow", statement["Effect"])
        self.assertEqual(["s3:PutObject"], statement["Action"])
        self.assertEqual("arn:aws:s3:::REPLACE_MEDIA_BUCKET/backups/estate/*", statement["Resource"])
        self.assertNotIn("Principal", statement)

    def test_timer_targets_exact_schema_backup_daily_seoul_with_catchup_and_no_env_expansion(self):
        timer = (DEPLOY / "systemd/metaround-estate-backup.timer").read_text()
        service = (DEPLOY / "systemd/metaround-estate-backup.service").read_text()
        self.assertIn("OnCalendar=*-*-* 02:15:00 Asia/Seoul", timer)
        self.assertIn("Persistent=true", timer)
        self.assertIn("RandomizedDelaySec=600", timer)
        self.assertIn("Unit=metaround-estate-backup.service", timer)
        self.assertIn("aws-cloudfront-local.preview.env", service)
        self.assertIn("/opt/metaround/current/BackEnd/deploy/backup-estate.py", service)
        self.assertIn("UMask=0077", service)
        self.assertNotIn("\nEnvironmentFile=", service)
        self.assertNotIn("docker stop", service)


if __name__ == "__main__":
    unittest.main()
