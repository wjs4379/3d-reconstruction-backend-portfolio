"""Deployment regressions: no AWS calls, Docker daemon or real credentials required."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("aws_environment", ROOT / "deploy/check-aws-env.py")
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


def valid_environment():
    return {
        "API_DOMAIN": "estate.unit.test", "AWS_REGION": "ap-northeast-2",
        "DB_URL": "jdbc:mysql://demo.unit.ap-northeast-2.rds.amazonaws.com:3306/spatial_asset_db?"
                  "sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/rds/rds-truststore.p12&"
                  "trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=changeit",
        "DB_USERNAME": "metaround_app", "DB_PASSWORD": "d" * 64, "JWT_SECRET": "j" * 64,
        "KAKAO_APP_ID": "123456", "KAKAO_REST_API_KEY": "test-public-client",
        "KAKAO_CLIENT_SECRET": "test-server-secret", "KAKAO_LOCAL_REST_API_KEY": "test-address-key",
        "VITE_KAKAO_REST_API_KEY": "test-public-client", "MEDIA_S3_BUCKET": "unit-test-private-media",
        "RDS_TRUST_DIRECTORY": "/etc/metaround/rds", "TLS_DIRECTORY": "/etc/metaround/tls",
        "ACME_WEBROOT": "/var/lib/metaround/acme",
        "VITE_MAP_TILE_URL": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "VITE_MAP_TILE_ATTRIBUTION": "OpenStreetMap contributors",
    }


class AwsPreparationTests(unittest.TestCase):
    def test_valid_offline_configuration(self):
        self.assertEqual([], config.validate(valid_environment(), check_files=False))

    def test_rejects_unverified_rds_and_master_credentials(self):
        for key, value in [("DB_URL", "jdbc:mysql://127.0.0.1/spatial_asset_db"),
                           ("DB_USERNAME", "admin"), ("DB_PASSWORD", "short"), ("JWT_SECRET", "short")]:
            with self.subTest(key=key):
                env = valid_environment()
                env[key] = value
                self.assertTrue(any(message.startswith(key + ":") for message in config.validate(env, False)))

    def test_rejects_public_link_local_reserved_and_ipv6_listeners(self):
        for value in ["0.0.0.0", "8.8.8.8", "169.254.169.254", "192.0.2.10", "::1"]:
            with self.subTest(value=value):
                env = valid_environment()
                env["APP_BIND_ADDRESS"] = value
                self.assertTrue(any(message.startswith("APP_BIND_ADDRESS:") for message in config.validate(env, False)))
        for value in ["127.0.0.1", "10.2.0.7", "172.31.0.5", "192.168.1.2"]:
            env = valid_environment()
            env["APP_BIND_ADDRESS"] = value
            self.assertEqual([], config.validate(env, False))

    def test_private_ai_dispatch_contract(self):
        env = valid_environment()
        env.update(AI_PIPELINE_ENABLED="true", OUTBOX_WORKER_ENABLED="true", AI_SERVICE_KEY="a" * 64,
                   APP_BIND_ADDRESS="10.0.1.7", APP_BACKEND_BASE_URL="http://10.0.1.7:8080",
                   AI_SERVER_BASE_URL="http://10.0.1.9:8000")
        self.assertEqual([], config.validate(env, False))
        for key, value in [("APP_BACKEND_BASE_URL", "http://10.0.1.8:8080"),
                           ("APP_BACKEND_BASE_URL", "http://10.0.1.7:443"),
                           ("APP_BACKEND_BASE_URL", "https://10.0.1.7:8080"),
                           ("AI_SERVER_BASE_URL", "http://169.254.169.254:8000"),
                           ("AI_SERVER_BASE_URL", "http://127.0.0.1:8000"),
                           ("OUTBOX_WORKER_ENABLED", "false")]:
            with self.subTest(key=key, value=value):
                bad = dict(env, **{key: value})
                self.assertTrue(config.validate(bad, False))

    def test_map_requires_https_and_tile_coordinates(self):
        for value in ["http://tiles.unit.test/{z}/{x}/{y}.png", "https://tiles.unit.test/image.png"]:
            env = valid_environment()
            env["VITE_MAP_TILE_URL"] = value
            self.assertTrue(any(message.startswith("VITE_MAP_TILE_URL:") for message in config.validate(env, False)))

    def test_shell_override_rejected_without_disclosing_values(self):
        env = valid_environment()
        private = "sensitive-shell-override"
        with patch.dict(os.environ, {"DB_PASSWORD": private}, clear=True):
            errors = config.validate_process_environment(env)
        self.assertEqual(1, len(errors))
        self.assertIn("DB_PASSWORD:", errors[0])
        self.assertNotIn(private, errors[0])
        with patch.dict(os.environ, {"DB_PASSWORD": env["DB_PASSWORD"]}, clear=True):
            self.assertEqual([], config.validate_process_environment(env))
        with patch.dict(os.environ, {"APP_BIND_ADDRESS": "0.0.0.0"}, clear=True):
            self.assertTrue(config.validate_process_environment(env))

    def test_parser_rejects_ambiguous_compose_values_and_shell_expansion(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "aws.env"
            for value in ["$(whoami)", "value # inline comment", r"value\nother", "'unclosed"]:
                with self.subTest(value=value):
                    path.write_text("JWT_SECRET=" + value + "\n")
                    with self.assertRaises(ValueError):
                        config.read_env(path)
            path.write_text("VALUE='literal # preserved'\n")
            self.assertEqual({"VALUE": "literal # preserved"}, config.read_env(path))

    def test_environment_permissions_checked_before_parsing(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "aws.env"
            path.write_text("JWT_SECRET=do-not-display\n")
            path.chmod(0o644)
            result = subprocess.run([sys.executable, str(ROOT / "deploy/check-aws-env.py"), str(path), "--skip-files"],
                                    text=True, capture_output=True)
            self.assertEqual(1, result.returncode)
            self.assertIn("permissions", result.stderr)
            self.assertNotIn("do-not-display", result.stdout + result.stderr)

    def test_redeploy_checks_then_reloads_existing_proxy(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            env = valid_environment()
            env.update(RDS_TRUST_DIRECTORY=str(directory / "rds"), TLS_DIRECTORY=str(directory / "tls"),
                       ACME_WEBROOT=str(directory / "acme"))
            (directory / "rds").mkdir()
            (directory / "rds/rds-truststore.p12").write_text("test fixture only")
            tls = directory / "tls/live/metaround"
            tls.mkdir(parents=True)
            (tls / "fullchain.pem").write_text("test fixture only")
            (tls / "privkey.pem").write_text("test fixture only")
            (directory / "acme").mkdir()
            env_file = directory / "aws.env"
            env_file.write_text("".join(key + "=" + value + "\n" for key, value in env.items()))
            env_file.chmod(0o600)
            docker = directory / "docker"
            docker.write_text("#!/bin/sh\nprintf '%s\n' \"$*\" >> \"$AWS_TEST_LOG\"\n")
            docker.chmod(0o700)
            process_env = {"PATH": str(directory) + os.pathsep + os.environ["PATH"],
                           "AWS_TEST_LOG": str(directory / "docker.log")}
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(env_file)],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = (directory / "docker.log").read_text().splitlines()
            self.assertEqual(4, len(commands))
            self.assertTrue(all("--project-name metaround-aws" in command for command in commands))
            self.assertTrue(commands[0].endswith("config --quiet"))
            self.assertTrue(commands[1].endswith("up -d --build --wait --wait-timeout 180"))
            self.assertTrue(commands[2].endswith("exec -T nginx nginx -t"))
            self.assertTrue(commands[3].endswith("exec -T nginx nginx -s reload"))
            self.assertNotIn(env["DB_PASSWORD"], result.stdout + result.stderr)

    def test_s3_transport_policy_denies_cleartext_without_granting_access(self):
        policy = json.loads((ROOT / "deploy/aws/s3-transport-policy.example.json").read_text())
        statement, = policy["Statement"]
        self.assertEqual("Deny", statement["Effect"])
        self.assertEqual({"Bool": {"aws:SecureTransport": "false"}}, statement["Condition"])
        self.assertEqual(2, len(statement["Resource"]))


if __name__ == "__main__":
    unittest.main()
