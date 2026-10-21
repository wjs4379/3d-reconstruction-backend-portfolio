"""Same-host CloudFront deployment never starts/mounts a second database."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_aws_preparation import ROOT, config
from test_cloudfront_preparation import cloudfront_environment


def local_environment():
    env = cloudfront_environment()
    env.pop("RDS_TRUST_DIRECTORY")
    env.update(
        DB_URL="jdbc:mysql://mysql:3306/metaround_estate_v5?serverTimezone=Asia/Seoul&"
               "sslMode=VERIFY_CA&trustCertificateKeyStoreUrl=file:/etc/mysql/mysql-truststore.p12&"
               "trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=changeit",
        DB_USERNAME="metaround_estate_app", MYSQL_TRUST_DIRECTORY="/etc/metaround/mysql",
        EXISTING_DB_NETWORK="gp-backend_default")
    return env


def prepare_local_runtime(directory):
    env = local_environment()
    env["MYSQL_TRUST_DIRECTORY"] = str(directory / "mysql-ca")
    (directory / "mysql-ca").mkdir()
    (directory / "mysql-ca/mysql-truststore.p12").write_text("public CA fixture only")
    env_file = directory / "aws.env"
    env_file.write_text("".join(key + "=" + value + "\n" for key, value in env.items()))
    env_file.chmod(0o600)
    docker = directory / "docker"
    docker.write_text("""#!/bin/sh
printf '%s\\n' "$*" >> "$AWS_TEST_LOG"
if [ "$1" = network ]; then
  printf '%s\\n' "${AWS_TEST_NETWORK_SCOPE:-bridge local}"
fi
""")
    docker.chmod(0o700)
    process_env = {"PATH": str(directory) + os.pathsep + os.environ["PATH"],
                   "AWS_TEST_LOG": str(directory / "docker.log")}
    return env, env_file, process_env


class CloudFrontLocalPreparationTests(unittest.TestCase):
    def test_local_mode_requires_existing_ca_and_network_without_rds_or_origin_cert(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, _, _ = prepare_local_runtime(Path(temporary))
            self.assertEqual([], config.validate(env, mode="cloudfront-local"))
            Path(env["MYSQL_TRUST_DIRECTORY"], "mysql-truststore.p12").unlink()
            errors = config.validate(env, mode="cloudfront-local")
            self.assertTrue(any(error.startswith("MYSQL_TRUST_DIRECTORY:") for error in errors))
            self.assertFalse(any(error.startswith(("RDS_TRUST_DIRECTORY:", "TLS_DIRECTORY:", "ACME_WEBROOT:"))
                                 for error in errors))

    def test_rejects_old_or_arbitrary_schema_before_any_database_action(self):
        for schema in ["spatial_asset_db", "mysql", "gp_estate_demo", "metaround_estate_v5/other", "metaround_estate_v5%00"]:
            with self.subTest(schema=schema):
                env = local_environment()
                env["DB_URL"] = env["DB_URL"].replace("/metaround_estate_v5?", "/" + schema + "?")
                self.assertTrue(any(error.startswith("DB_URL:") for error in
                                    config.validate(env, check_files=False, mode="cloudfront-local")))

    def test_rejects_remote_ip_dotted_or_userinfo_database_target(self):
        for host in ["127.0.0.1", "10.0.0.7", "localhost", "mysql.unit.test", "mysql.",
                     "db.ap-northeast-2.rds.amazonaws.com", "root@mysql", "mysql:3307", "[::1]"]:
            with self.subTest(host=host):
                env = local_environment()
                env["DB_URL"] = env["DB_URL"].replace("mysql:3306/", host + "/")
                self.assertTrue(any(error.startswith("DB_URL:") for error in
                                    config.validate(env, check_files=False, mode="cloudfront-local")))

    def test_requires_verify_ca_without_plaintext_duplicate_or_key_retrieval(self):
        for value in ["DISABLED", "PREFERRED", "REQUIRED", "VERIFY_IDENTITY", "VERIFY_CA&sslMode=DISABLED"]:
            env = local_environment()
            env["DB_URL"] = env["DB_URL"].replace("sslMode=VERIFY_CA", "sslMode=" + value)
            self.assertTrue(any(error.startswith("DB_URL:") for error in
                                config.validate(env, check_files=False, mode="cloudfront-local")))
        for extra in ["allowPublicKeyRetrieval=true", "allowPublicKeyRetrieval=false",
                      "socketFactory=remote.Factory", "useSSL=false"]:
            env = local_environment()
            env["DB_URL"] += "&" + extra
            self.assertTrue(any(error.startswith("DB_URL:") for error in
                                config.validate(env, check_files=False, mode="cloudfront-local")))

    def test_local_ca_path_type_and_password_are_fixed(self):
        for old, new in [("file:/etc/mysql/mysql-truststore.p12", "file:/etc/rds/rds-truststore.p12"),
                         ("trustCertificateKeyStoreType=PKCS12", "trustCertificateKeyStoreType=JKS"),
                         ("trustCertificateKeyStorePassword=changeit", "trustCertificateKeyStorePassword=other")]:
            env = local_environment()
            env["DB_URL"] = env["DB_URL"].replace(old, new)
            self.assertTrue(any(error.startswith("DB_URL:") for error in
                                config.validate(env, check_files=False, mode="cloudfront-local")))

    def test_tls_and_cloudfront_rds_modes_do_not_accept_local_database_exception(self):
        for mode in ["tls", "cloudfront"]:
            errors = config.validate(local_environment(), check_files=False, mode=mode)
            self.assertTrue(any(error.startswith("DB_URL:") for error in errors))
            self.assertTrue(any(error.startswith("RDS_TRUST_DIRECTORY:") for error in errors))

    def test_local_mode_keeps_public_boundary_oauth_and_admin_guards(self):
        for key, value in [("API_DOMAIN", "estate.unit.test"), ("CLOUDFRONT_ORIGIN_SECRET", ""),
                           ("KAKAO_CLIENT_SECRET", ""), ("DB_USERNAME", "root"),
                           ("APP_BIND_ADDRESS", "0.0.0.0"), ("EXISTING_DB_NETWORK", "host"),
                           ("EXISTING_DB_NETWORK", "bridge"), ("EXISTING_DB_NETWORK", "none"),
                           ("EXISTING_DB_NETWORK", "missing network")]:
            with self.subTest(key=key):
                errors = config.validate(dict(local_environment(), **{key: value}),
                                         check_files=False, mode="cloudfront-local")
                self.assertTrue(any(error.startswith(key + ":") for error in errors))

    def test_local_network_and_trust_directory_shell_overrides_are_checked(self):
        with patch.dict(os.environ, {"EXISTING_DB_NETWORK": "other-network", "MYSQL_TRUST_DIRECTORY": "/wrong"},
                        clear=True):
            errors = config.validate_process_environment(local_environment(), "cloudfront-local")
            self.assertEqual(2, len(errors))
            self.assertTrue(any(error.startswith("EXISTING_DB_NETWORK:") for error in errors))
            self.assertTrue(any(error.startswith("MYSQL_TRUST_DIRECTORY:") for error in errors))

    def test_local_redeploy_reads_existing_network_then_only_starts_app_stack(self):
        with tempfile.TemporaryDirectory() as temporary:
            _, path, process_env = prepare_local_runtime(Path(temporary))
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(path), "cloudfront-local"],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()
            self.assertEqual(5, len(commands))
            self.assertTrue(commands[0].startswith("network inspect --format "))
            self.assertTrue(commands[1].endswith("config --quiet"))
            self.assertTrue(all("-f compose.aws-cloudfront-local.yaml" in command for command in commands[1:]))
            self.assertTrue(commands[2].endswith("up -d --build --wait --wait-timeout 180"))
            self.assertTrue(commands[4].endswith("exec -T nginx nginx -s reload"))
            self.assertFalse(any("network create" in command or "volume" in command for command in commands))

    def test_remote_or_builtin_network_driver_cannot_start_compose(self):
        for scope in ["overlay swarm", "host local", "bridge swarm", ""]:
            with self.subTest(scope=scope), tempfile.TemporaryDirectory() as temporary:
                _, path, process_env = prepare_local_runtime(Path(temporary))
                process_env["AWS_TEST_NETWORK_SCOPE"] = scope or "invalid"
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(path), "cloudfront-local"],
                                        env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                commands = Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()
                self.assertEqual(1, len(commands))
                self.assertTrue(commands[0].startswith("network inspect "))

    def test_profile_has_no_database_service_or_database_volume_mount(self):
        compose = (ROOT / "compose.aws-cloudfront-local.yaml").read_text()
        services = compose.split("services:\n", 1)[1].split("\nnetworks:", 1)[0]
        self.assertEqual({"app", "web", "nginx"}, set(re.findall(r"^  ([a-z_]+):$", services, re.M)))
        self.assertNotIn("/var/lib/mysql", compose)
        self.assertNotIn("mysql_data", compose)
        self.assertNotRegex(compose, r"(?m)^volumes:")
        self.assertNotRegex(compose, r"(?m)^\s*image:\s*mysql")
        self.assertIn("networks: [default, existing_db]", compose)
        self.assertIn("external: true", compose)
        self.assertIn("name: ${EXISTING_DB_NETWORK:", compose)
        self.assertIn(":/etc/mysql:ro", compose)


class PublicMySqlCaPreparationTests(unittest.TestCase):
    def fake_docker(self, directory):
        docker = directory / "docker"
        docker.write_text("""#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ["AWS_TEST_LOG"],"a") as log: log.write(json.dumps(args)+"\\n")
if args[0]=="cp":
 if os.environ.get("AWS_TEST_PEM_FILE"):
  Path(args[2]).write_bytes(Path(os.environ["AWS_TEST_PEM_FILE"]).read_bytes())
 else:
  Path(args[2]).write_text(os.environ.get("AWS_TEST_PEM", "-----BEGIN CERTIFICATE-----\\nYWJjZA==\\n-----END CERTIFICATE-----\\n"))
elif args[0]=="run":
 host=Path(args[args.index("-v")+1].rsplit(":",1)[0])
 (host/"mysql-truststore.p12").write_text("public-CA-only test fixture")
else: raise SystemExit(99)
""")
        docker.chmod(0o700)
        return {"PATH": str(directory) + os.pathsep + os.environ["PATH"],
                "AWS_TEST_LOG": str(directory / "docker.log")}

    def test_copies_only_ca_from_exact_path_and_never_original_volume_or_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            process_env = self.fake_docker(directory)
            output = directory / "ca-output"
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/prepare-local-mysql-trust.sh"),
                                     "gp-backend-mysql-1", str(output)],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = [json.loads(line) for line in Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()]
            self.assertEqual(2, len(commands))
            self.assertEqual(["cp", "gp-backend-mysql-1:/var/lib/mysql/ca.pem"], commands[0][:2])
            self.assertEqual("run", commands[1][0])
            self.assertIn("keytool", commands[1])
            self.assertIn("/work/ca.pem", commands[1])
            self.assertNotIn("gp-backend_mysql_data", " ".join(commands[1]))
            self.assertEqual({"mysql-ca.pem", "mysql-truststore.p12"}, {p.name for p in output.iterdir()})
            self.assertEqual(0o644, (output / "mysql-ca.pem").stat().st_mode & 0o777)

    def test_private_key_or_mixed_pem_cannot_create_final_truststore(self):
        for pem in ["-----BEGIN PRIVATE KEY-----\nnot-a-real-key\n-----END PRIVATE KEY-----\n",
                    "-----BEGIN CERTIFICATE-----\nYWJjZA==\n-----END CERTIFICATE-----\nPRIVATE KEY"]:
            with self.subTest(pem_type=pem.splitlines()[0]), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                process_env = self.fake_docker(directory)
                process_env["AWS_TEST_PEM"] = pem
                output = directory / "ca-output"
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/prepare-local-mysql-trust.sh"),
                                         "gp-backend-mysql-1", str(output)],
                                        env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                commands = [json.loads(line) for line in Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()]
                self.assertEqual(1, len(commands))
                self.assertFalse((output / "mysql-truststore.p12").exists())
                self.assertEqual([], list(output.iterdir()))

    def test_mysql_single_terminal_nul_is_normalized_in_copy_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            process_env = self.fake_docker(directory)
            canonical = b"-----BEGIN CERTIFICATE-----\nYWJjZA==\n-----END CERTIFICATE-----\n"
            original = directory / "original-ca.pem"
            original.write_bytes(canonical + b"\0")
            process_env["AWS_TEST_PEM_FILE"] = str(original)
            output = directory / "ca-output"
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/prepare-local-mysql-trust.sh"),
                                     "gp-backend-mysql-1", str(output)],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(canonical, (output / "mysql-ca.pem").read_bytes())
            self.assertEqual(canonical + b"\0", original.read_bytes())
            self.assertTrue((output / "mysql-truststore.p12").exists())

    def test_embedded_repeated_nul_or_hidden_mixed_pem_is_rejected(self):
        canonical = b"-----BEGIN CERTIFICATE-----\nYWJjZA==\n-----END CERTIFICATE-----\n"
        for invalid in [canonical + b"\0\0", canonical.replace(b"YWJj", b"YW\0Jj"),
                        canonical + b"\0PRIVATE KEY", canonical + b"PRIVATE KEY\0"]:
            with self.subTest(case=invalid), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                process_env = self.fake_docker(directory)
                original = directory / "original-ca.pem"
                original.write_bytes(invalid)
                process_env["AWS_TEST_PEM_FILE"] = str(original)
                output = directory / "ca-output"
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/prepare-local-mysql-trust.sh"),
                                         "gp-backend-mysql-1", str(output)],
                                        env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                commands = [json.loads(line) for line in Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()]
                self.assertEqual(1, len(commands))
                self.assertEqual(invalid, original.read_bytes())
                self.assertEqual([], list(output.iterdir()))

    def test_invalid_container_or_relative_output_is_rejected_before_docker(self):
        for container, output in [("../mysql", "unused"), ("mysql:other", "unused"), ("mysql", "relative")]:
            with self.subTest(container=container), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                process_env = self.fake_docker(directory)
                destination = str(directory / output) if output != "relative" else output
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/prepare-local-mysql-trust.sh"),
                                         container, destination], env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(Path(process_env["AWS_TEST_LOG"]).exists())


if __name__ == "__main__":
    unittest.main()
