"""Failure regressions for the destructive boundaries of the local-only demo helper."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("estate_local_demo", Path(__file__).resolve().parents[1] / "local_demo.py")
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)


class OwnershipTests(unittest.TestCase):
    def check_owned(self, record, args, birth="Mon Oct 12 14:00:00 2026"):
        with patch.object(demo.os, "getpgid", return_value=1234), patch.object(demo.os, "kill"), \
             patch.object(demo, "process_snapshot", return_value=(birth, args)):
            return demo.process_owned(record)

    def test_unrelated_pid_reuse_is_not_a_managed_gradle_process(self):
        record = {"pid": 1234, "command": ["/demo/BackEnd/gradlew", "--no-daemon", "estateDemo"]}
        self.assertFalse(self.check_owned(record, "/bin/sleep 1000"))

    def test_same_mysql_binary_with_different_data_directory_is_not_owned(self):
        record = {"pid": 1234, "command": ["/mysql/bin/mysqld", "--datadir=/demo/mysql-data", "--port=3327"]}
        self.assertFalse(self.check_owned(record, "/mysql/bin/mysqld --datadir=/system/mysql-data --port=3327"))

    def test_same_wrapper_for_different_gradle_task_is_not_owned(self):
        record = {"pid": 1234, "command": ["/demo/BackEnd/gradlew", "--no-daemon", "estateDemo"]}
        self.assertFalse(self.check_owned(record, "/java -jar /demo/BackEnd/gradle/wrapper/gradle-wrapper.jar --no-daemon bootRun"))

    def test_real_gradle_jar_launcher_is_owned(self):
        record = {"pid": 1234, "command": ["/demo/BackEnd/gradlew", "--no-daemon", "estateDemo"]}
        self.assertTrue(self.check_owned(record, "/java -jar /demo/BackEnd/gradle/wrapper/gradle-wrapper.jar --no-daemon estateDemo"))

    def test_same_command_but_different_process_birth_is_not_owned(self):
        record = {"pid": 1234, "birthTime": "Mon Oct 12 13:00:00 2026", "command": ["/bin/sleep", "1000"]}
        self.assertFalse(self.check_owned(record, "/bin/sleep 1000"))

    def test_stale_record_cannot_adopt_an_unrelated_listening_server(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime = Path(tmp)
            demo.private_json(runtime / "backend.process.json", {"pid": 1234, "command": ["/bin/sleep", "1000"]})
            with patch.object(demo, "listening", return_value=True), patch.object(demo, "process_owned", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "unmanaged"):
                    demo.require_port_available_or_owned(runtime, "backend", 8082)

    def test_stop_waits_for_gradle_children_after_wrapper_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime = Path(tmp)
            demo.private_json(runtime / "backend.process.json", {"pid": 1234, "command": ["/demo/gradlew"]})
            with patch.object(demo, "process_owned", return_value=True), patch.object(demo, "process_group_alive", side_effect=[True, False]), \
                 patch.object(demo.os, "killpg") as kill, patch.object(demo.time, "sleep") as sleep:
                demo.stop_process(runtime, "backend")
                kill.assert_called_once_with(1234, signal.SIGTERM)
                sleep.assert_called_once()
            self.assertFalse((runtime / "backend.process.json").exists())


class PrivateRuntimeTests(unittest.TestCase):
    def test_secret_write_rejects_symlink_without_modifying_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "unchanged.txt"
            target.write_text("keep")
            link = Path(tmp) / "secrets.json"
            link.symlink_to(target)
            with self.assertRaisesRegex(RuntimeError, "symbolic"):
                demo.private_json(link, {"secret": "replacement"})
            self.assertEqual(target.read_text(), "keep")

    def test_secret_write_is_private_and_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "secrets.json"
            demo.private_json(path, {"version": 1})
            demo.private_json(path, {"version": 2})
            self.assertEqual(json.loads(path.read_text()), {"version": 2})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def mutate_configuration(self, change):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            change(config)
            demo.private_json(runtime / demo.CONFIG_NAME, config)
            with self.assertRaises(RuntimeError):
                demo.configuration(runtime)

    def test_root_client_stays_on_private_socket_while_app_client_uses_loopback_tcp(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            with patch.object(demo, "executable", return_value="/mysql/bin/mysql"):
                demo.mysql_command(runtime, config, "/mysql", root=True)
                demo.mysql_command(runtime, config, "/mysql")
            root_settings = (runtime / "root-client.cnf").read_text()
            app_settings = (runtime / "app-client.cnf").read_text()
            self.assertIn("protocol=socket\n", root_settings)
            self.assertIn("socket=" + str(runtime / "mysql.sock") + "\n", root_settings)
            self.assertNotIn("protocol=tcp", root_settings)
            self.assertIn("protocol=tcp\n", app_settings)
            self.assertIn("host=127.0.0.1\n", app_settings)

    def test_sql_syntax_in_config_secret_is_rejected_before_bootstrap(self):
        self.mutate_configuration(lambda config: config.update(dbPassword="x' OR '1'='1"))

    def test_boolean_and_duplicate_ports_are_rejected(self):
        self.mutate_configuration(lambda config: config.update(mysqlPort=True))
        self.mutate_configuration(lambda config: config.update(mysqlPort=config["backendPort"]))


class RecoveryTests(unittest.TestCase):
    def test_partial_socket_bootstrap_retries_with_secured_root_and_marks_only_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            secured_commands = []
            should_fail = [True]
            def run(command, **kwargs):
                if kwargs.get("input") == "SELECT 1;":
                    return SimpleNamespace(returncode=1 if "--no-defaults" in command else 0)
                if kwargs.get("input") == "SELECT @@GLOBAL.partial_revokes;":
                    return SimpleNamespace(returncode=0, stdout="0\n")
                secured_commands.append((command, kwargs["input"]))
                return SimpleNamespace(returncode=1 if should_fail[0] else 0)
            with patch.object(demo, "executable", side_effect=lambda home, name: "/mysql/bin/" + name), \
                 patch.object(demo, "record_process"), patch.object(demo, "stop_process") as stop, patch.object(demo.subprocess, "run", side_effect=run):
                with self.assertRaisesRegex(RuntimeError, "retry safely"):
                    demo.secure_mysql_bootstrap(runtime, config, "/mysql")
                self.assertFalse((runtime / "mysql-bootstrap-complete.json").exists())
                should_fail[0] = False
                demo.secure_mysql_bootstrap(runtime, config, "/mysql")
                self.assertTrue((runtime / "mysql-bootstrap-complete.json").is_file())
                self.assertEqual(stop.call_count, 2)
            self.assertTrue(all(any(part.startswith("--defaults-file=") for part in cmd) for cmd, _ in secured_commands))
            self.assertTrue(all("CREATE DATABASE IF NOT EXISTS" in sql and "CREATE USER IF NOT EXISTS" in sql for _, sql in secured_commands))
            self.assertTrue(all(r"ON `gp\_estate\_demo`.*" in sql and r"ON `estate\_migration\_test`.*" in sql for _, sql in secured_commands))
            self.assertTrue(all("REVOKE ALL PRIVILEGES, GRANT OPTION" in sql for _, sql in secured_commands))

    def test_partial_revokes_on_bootstrap_keeps_literal_underscores(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            statements = []
            def run(command, **kwargs):
                text = kwargs.get("input")
                if text == "SELECT @@GLOBAL.partial_revokes;":
                    return SimpleNamespace(returncode=0, stdout="@@GLOBAL.partial_revokes\n1\n")
                if text != "SELECT 1;":
                    statements.append(text)
                return SimpleNamespace(returncode=0)
            with patch.object(demo, "executable", side_effect=lambda home, name: "/mysql/bin/" + name), \
                 patch.object(demo, "record_process"), patch.object(demo, "stop_process"), \
                 patch.object(demo.subprocess, "run", side_effect=run):
                demo.secure_mysql_bootstrap(runtime, config, "/mysql")
            self.assertEqual(len(statements), 1)
            self.assertIn("ON `gp_estate_demo`.*", statements[0])
            self.assertIn("ON `estate_migration_test`.*", statements[0])
            self.assertNotIn(r"gp\_estate", statements[0])

    def test_default_backup_parent_is_private_before_snapshot_starts(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            parent = runtime / "backups"
            parent.mkdir(mode=0o755)
            parent.chmod(0o755)
            def snapshot(args, runtime, config, destination):
                self.assertEqual(parent.stat().st_mode & 0o777, 0o700)
                raise RuntimeError("test snapshot stop")
            args = SimpleNamespace(output=None, mysql_home="/mysql")
            with patch.object(demo, "listening", return_value=False), patch.object(demo, "create_backup_files", side_effect=snapshot):
                with self.assertRaisesRegex(RuntimeError, "test snapshot stop"):
                    demo.backup(args, runtime, config)

    def test_new_backup_manifest_includes_dump_window_database_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            parent = runtime / "backups"
            parent.mkdir(mode=0o700)
            destination = parent / "snapshot"
            events = []
            fingerprint = {"format": 1, "tableCount": 16, "tables": []}
            def dump(command, **kwargs):
                events.append("dump")
                kwargs["stdout"].write(b"-- private test dump\n")
                return SimpleNamespace(returncode=0)
            def capture(query, schema, row_digest):
                self.assertEqual(events, ["dump"])
                self.assertEqual((destination / "backend.sql").read_bytes(), b"-- private test dump\n")
                self.assertEqual(schema, "gp_estate_demo")
                events.append("fingerprint")
                return fingerprint
            args = SimpleNamespace(mysql_home="/mysql")
            with patch.object(demo, "mysql_command", return_value=["/mysql/bin/mysqldump"]), \
                 patch.object(demo.subprocess, "run", side_effect=dump), \
                 patch.object(demo, "database_fingerprint", side_effect=capture):
                demo.create_backup_files(args, runtime, config, destination)
            manifest = json.loads((destination / "backup-manifest.json").read_text())
            self.assertEqual(manifest["version"], 1)
            self.assertEqual(manifest["databaseFingerprint"], fingerprint)
            self.assertEqual(events, ["dump", "fingerprint"])
            self.assertTrue({"backend.sql", "media.tar.gz", "local-secrets.json"}.issubset(manifest["files"]))
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in destination.iterdir()))

    def test_failed_backup_restarts_only_the_writers_it_paused(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, config = demo.configuration(Path(tmp))
            for name in ("ai", "backend"):
                demo.private_json(runtime / (name + ".process.json"), {"pid": 1234, "command": ["/demo/" + name], "cwd": str(runtime)})
            args = SimpleNamespace(output=str(runtime / "backup-new"), mysql_home="/mysql")
            with patch.object(demo, "process_owned", return_value=True), patch.object(demo, "listening", return_value=True), \
                 patch.object(demo, "stop_process") as stop, patch.object(demo, "record_process") as start, \
                 patch.object(demo, "backend_environment", return_value={}), patch.object(demo, "wait_http"), \
                 patch.object(demo, "create_backup_files", side_effect=RuntimeError("dump failed")):
                with self.assertRaisesRegex(RuntimeError, "dump failed"):
                    demo.backup(args, runtime, config)
            self.assertEqual([call.args[1] for call in stop.call_args_list], ["ai", "backend"])
            self.assertEqual([call.args[1] for call in start.call_args_list], ["backend", "ai"])


if __name__ == "__main__":
    unittest.main()
