"""CloudFront deployment safeguards. Uses only fixture files and fake Docker/curl."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_aws_preparation import ROOT, config, valid_environment


def cloudfront_environment():
    env = valid_environment()
    env.pop("TLS_DIRECTORY")
    env.pop("ACME_WEBROOT")
    env.update(API_DOMAIN="d123456example.cloudfront.net", CLOUDFRONT_ORIGIN_SECRET="a" * 64)
    return env


def prepare_runtime(directory):
    env = cloudfront_environment()
    env["RDS_TRUST_DIRECTORY"] = str(directory / "rds")
    (directory / "rds").mkdir()
    (directory / "rds/rds-truststore.p12").write_text("public CA test fixture only")
    env_file = directory / "aws.env"
    env_file.write_text("".join(key + "=" + value + "\n" for key, value in env.items()))
    env_file.chmod(0o600)
    docker = directory / "docker"
    docker.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$AWS_TEST_LOG\"\n")
    docker.chmod(0o700)
    process_env = {"PATH": str(directory) + os.pathsep + os.environ["PATH"],
                   "AWS_TEST_LOG": str(directory / "docker.log")}
    return env, env_file, process_env


class CloudFrontPreparationTests(unittest.TestCase):
    def test_cloudfront_files_require_rds_ca_but_not_origin_certificate(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, _, _ = prepare_runtime(Path(temporary))
            self.assertEqual([], config.validate(env, mode="cloudfront"))
            Path(env["RDS_TRUST_DIRECTORY"], "rds-truststore.p12").unlink()
            errors = config.validate(env, mode="cloudfront")
            self.assertTrue(any(error.startswith("RDS_TRUST_DIRECTORY:") for error in errors))
            self.assertFalse(any(error.startswith(("TLS_DIRECTORY:", "ACME_WEBROOT:")) for error in errors))

    def test_default_mode_preserves_direct_tls_requirements(self):
        errors = config.validate(cloudfront_environment(), check_files=False)
        self.assertTrue(any(error.startswith("TLS_DIRECTORY:") for error in errors))
        self.assertTrue(any(error.startswith("ACME_WEBROOT:") for error in errors))
        self.assertEqual([], config.validate(valid_environment(), check_files=False))

    def test_rejects_non_distribution_and_host_injection(self):
        for hostname in ["estate.unit.test", "cloudfront.net", "other.cloudfront.net",
                         "d123456.cloudfront.net.evil.test", "https://d123456.cloudfront.net",
                         "d123456.cloudfront.net/path", "d123456.cloudfront.net:80",
                         'd123456.cloudfront.net";', "D123456.cloudfront.net"]:
            with self.subTest(hostname=hostname):
                env = dict(cloudfront_environment(), API_DOMAIN=hostname)
                self.assertTrue(any(error.startswith("API_DOMAIN:") for error in
                                    config.validate(env, check_files=False, mode="cloudfront")))

    def test_origin_secret_is_strong_and_safe_for_nginx_substitution(self):
        for secret in ["", "a" * 62, "a" * 130, "A" * 64, "z" * 64, "a" * 63 + ";",
                       'a" } location / { return 200; } #', "a" * 64 + "\n"]:
            with self.subTest(length=len(secret)):
                errors = config.validate(dict(cloudfront_environment(), CLOUDFRONT_ORIGIN_SECRET=secret),
                                         check_files=False, mode="cloudfront")
                self.assertTrue(any(error.startswith("CLOUDFRONT_ORIGIN_SECRET:") for error in errors))
                if secret:
                    self.assertNotIn(secret, "\n".join(errors))
        for length in [64, 96, 128]:
            self.assertEqual([], config.validate(dict(cloudfront_environment(), CLOUDFRONT_ORIGIN_SECRET="a" * length),
                                                check_files=False, mode="cloudfront"))

    def test_cloudfront_keeps_database_oauth_and_private_listener_checks(self):
        for key, value in [("DB_URL", "jdbc:mysql://127.0.0.1/spatial_asset_db"),
                           ("KAKAO_CLIENT_SECRET", ""), ("KAKAO_APP_ID", "not-a-number"),
                           ("APP_BIND_ADDRESS", "0.0.0.0")]:
            with self.subTest(key=key):
                errors = config.validate(dict(cloudfront_environment(), **{key: value}),
                                         check_files=False, mode="cloudfront")
                self.assertTrue(any(error.startswith(key + ":") for error in errors))

    def test_cloudfront_shell_secret_override_is_rejected_without_leaking(self):
        secret = "b" * 64
        with patch.dict(os.environ, {"CLOUDFRONT_ORIGIN_SECRET": secret}, clear=True):
            errors = config.validate_process_environment(cloudfront_environment(), "cloudfront")
            self.assertEqual(1, len(errors))
            self.assertIn("CLOUDFRONT_ORIGIN_SECRET:", errors[0])
            self.assertNotIn(secret, errors[0])
            self.assertEqual([], config.validate_process_environment(valid_environment()))
        with patch.dict(os.environ, {"CLOUDFRONT_ORIGIN_SECRET": "a" * 64}, clear=True):
            self.assertEqual([], config.validate_process_environment(cloudfront_environment(), "cloudfront"))

    def test_cli_rejects_bad_secret_without_printing_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, path, process_env = prepare_runtime(Path(temporary))
            private = "invalid-sensitive-origin-value"
            path.write_text(path.read_text().replace(env["CLOUDFRONT_ORIGIN_SECRET"], private))
            result = subprocess.run([sys.executable, str(ROOT / "deploy/check-aws-env.py"), str(path),
                                     "--mode", "cloudfront"], text=True, capture_output=True, env=process_env)
            self.assertEqual(1, result.returncode)
            self.assertIn("CLOUDFRONT_ORIGIN_SECRET:", result.stderr)
            self.assertNotIn(private, result.stdout + result.stderr)

    def test_cloudfront_redeploy_uses_selected_stack_then_reloads_proxy(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, path, process_env = prepare_runtime(Path(temporary))
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(path), "cloudfront"],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()
            self.assertEqual(4, len(commands))
            self.assertTrue(all("-f compose.aws-cloudfront.yaml" in command for command in commands))
            self.assertTrue(all("--project-name metaround-aws" in command for command in commands))
            self.assertTrue(commands[0].endswith("config --quiet"))
            self.assertTrue(commands[1].endswith("up -d --build --wait --wait-timeout 180"))
            self.assertTrue(commands[2].endswith("exec -T nginx nginx -t"))
            self.assertTrue(commands[3].endswith("exec -T nginx nginx -s reload"))
            self.assertNotIn(env["CLOUDFRONT_ORIGIN_SECRET"], result.stdout + result.stderr)

    def test_cloudfront_check_is_read_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            _, path, process_env = prepare_runtime(Path(temporary))
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "check", str(path), "cloudfront"],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()
            self.assertEqual(2, len(commands))
            self.assertTrue(commands[0].endswith("config --quiet"))
            self.assertEqual("info", commands[1])
            self.assertFalse(any(" up " in command for command in commands))

    def test_unknown_mode_cannot_skip_existing_checks_or_invoke_docker(self):
        with self.assertRaises(ValueError):
            config.validate(valid_environment(), check_files=False, mode="invalid")
        with tempfile.TemporaryDirectory() as temporary:
            _, path, process_env = prepare_runtime(Path(temporary))
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(path), "invalid"],
                                    env=process_env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("mode", result.stderr)
            self.assertFalse(Path(process_env["AWS_TEST_LOG"]).exists())

    def test_distribution_disables_shared_auth_redirect_and_error_caches(self):
        distribution = json.loads((ROOT / "deploy/aws/cloudfront-distribution.example.json").read_text())
        behavior = distribution["DefaultCacheBehavior"]
        self.assertEqual("redirect-to-https", behavior["ViewerProtocolPolicy"])
        self.assertEqual("4135ea2d-6df8-44a3-9df3-4b5a84be39ad", behavior["CachePolicyId"])
        self.assertEqual("216adef6-5c7f-47e4-b989-5492eafa07d3", behavior["OriginRequestPolicyId"])
        self.assertEqual({"GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE"},
                         set(behavior["AllowedMethods"]["Items"]))
        self.assertTrue(distribution["ViewerCertificate"]["CloudFrontDefaultCertificate"])
        origin, = distribution["Origins"]["Items"]
        self.assertEqual("http-only", origin["CustomOriginConfig"]["OriginProtocolPolicy"])
        self.assertEqual("X-Metaround-Origin", origin["CustomHeaders"]["Items"][0]["HeaderName"])
        self.assertIn("replace-with", origin["CustomHeaders"]["Items"][0]["HeaderValue"])
        for error in distribution["CustomErrorResponses"]["Items"]:
            self.assertEqual(0, error["ErrorCachingMinTTL"])
            self.assertNotIn("ResponseCode", error)
            self.assertNotIn("ResponsePagePath", error)

    def test_origin_template_protects_internal_paths_without_redirect_loop_or_secret_logs(self):
        template = (ROOT / "deploy/nginx/cloudfront.conf.template").read_text()
        self.assertIn('if ($http_x_metaround_origin != "${CLOUDFRONT_ORIGIN_SECRET}") { return 404; }', template)
        self.assertIn("location ^~ /api/internal/ai/ { return 404; }", template)
        self.assertIn("location ^~ /actuator/ { return 404; }", template)
        self.assertIn("proxy_pass http://app:8080;", template)
        self.assertIn("proxy_pass http://web:8080;", template)
        self.assertNotIn("return 308", template)
        self.assertNotIn("return 301", template)
        self.assertNotIn("ssl_certificate", template)
        self.assertEqual(2, template.count('proxy_set_header X-Metaround-Origin "";'))
        log_format = next(line for line in template.splitlines() if line.startswith("log_format"))
        self.assertNotIn("$request_uri", log_format)
        self.assertNotIn("$http_x_metaround_origin", log_format)


if __name__ == "__main__":
    unittest.main()
