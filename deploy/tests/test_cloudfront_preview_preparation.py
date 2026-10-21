"""Explicit public-only preview keeps real login and management closed."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_aws_preparation import ROOT, config, valid_environment
from test_cloudfront_preparation import cloudfront_environment
from test_cloudfront_local_preparation import local_environment, prepare_local_runtime

OAUTH_KEYS = ["KAKAO_APP_ID", "KAKAO_REST_API_KEY", "KAKAO_CLIENT_SECRET",
              "KAKAO_LOCAL_REST_API_KEY", "VITE_KAKAO_REST_API_KEY", "GOOGLE_CLIENT_ID"]


def preview_environment():
    env = local_environment()
    env.update({key: "" for key in OAUTH_KEYS})
    env.update(AI_PIPELINE_ENABLED="false", OUTBOX_WORKER_ENABLED="false")
    return env


def prepare_preview_runtime(directory):
    env, path, process_env = prepare_local_runtime(directory)
    env.update({key: "" for key in OAUTH_KEYS})
    env.update(AI_PIPELINE_ENABLED="false", OUTBOX_WORKER_ENABLED="false")
    path.write_text("".join(key + "=" + value + "\n" for key, value in env.items()))
    return env, path, process_env


class CloudFrontPreviewPreparationTests(unittest.TestCase):
    def test_only_explicit_preview_accepts_all_blank_keys(self):
        env = preview_environment()
        self.assertEqual([], config.validate(env, check_files=False, mode="cloudfront-local",
                                            preview_without_oauth=True))
        errors = config.validate(env, check_files=False, mode="cloudfront-local")
        self.assertTrue(any(error.startswith("KAKAO_REST_API_KEY:") for error in errors))
        self.assertTrue(any(error.startswith("KAKAO_CLIENT_SECRET:") for error in errors))

    def test_other_modes_cannot_apply_preview_exception(self):
        for mode, env in [("tls", valid_environment()), ("cloudfront", cloudfront_environment())]:
            with self.subTest(mode=mode):
                errors = config.validate(env, check_files=False, mode=mode, preview_without_oauth=True)
                self.assertTrue(any(error.startswith("PREVIEW_STAGE:") for error in errors))

    def test_partial_or_placeholder_oauth_configuration_is_not_preview(self):
        for key in OAUTH_KEYS:
            for value in ["partially-configured", "replace-with-placeholder", " "]:
                with self.subTest(key=key):
                    errors = config.validate(dict(preview_environment(), **{key: value}),
                                             check_files=False, mode="cloudfront-local", preview_without_oauth=True)
                    self.assertTrue(any(error.startswith(key + ":") for error in errors))
                    if value.strip():
                        self.assertNotIn(value, "\n".join(errors))

    def test_preview_cannot_run_ai_or_dispatch_worker(self):
        for key in ["AI_PIPELINE_ENABLED", "OUTBOX_WORKER_ENABLED"]:
            errors = config.validate(dict(preview_environment(), **{key: "true"}), check_files=False,
                                     mode="cloudfront-local", preview_without_oauth=True)
            self.assertTrue(any(error.startswith("PREVIEW_STAGE:") for error in errors))

    def test_preview_still_requires_new_schema_verified_ca_jwt_s3_origin_and_network(self):
        env = preview_environment()
        for key, value in [("DB_URL", env["DB_URL"].replace("metaround_estate_v5", "spatial_asset_db")),
                           ("DB_URL", env["DB_URL"].replace("VERIFY_CA", "DISABLED")),
                           ("JWT_SECRET", "short"), ("MEDIA_S3_BUCKET", ""),
                           ("CLOUDFRONT_ORIGIN_SECRET", ""), ("EXISTING_DB_NETWORK", "host"),
                           ("MYSQL_TRUST_DIRECTORY", "relative"), ("APP_BIND_ADDRESS", "0.0.0.0")]:
            with self.subTest(key=key):
                errors = config.validate(dict(env, **{key: value}), check_files=False,
                                         mode="cloudfront-local", preview_without_oauth=True)
                self.assertTrue(any(error.startswith(key + ":") for error in errors))

    def test_preview_ca_file_cannot_be_skipped_by_stage(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, _, _ = prepare_preview_runtime(Path(temporary))
            self.assertEqual([], config.validate(env, mode="cloudfront-local", preview_without_oauth=True))
            Path(env["MYSQL_TRUST_DIRECTORY"], "mysql-truststore.p12").unlink()
            errors = config.validate(env, mode="cloudfront-local", preview_without_oauth=True)
            self.assertTrue(any(error.startswith("MYSQL_TRUST_DIRECTORY:") for error in errors))

    def test_preview_shell_flag_is_required_before_any_deployment_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, path, process_env = prepare_preview_runtime(directory)
            command = ["/bin/sh", str(ROOT / "deploy/aws.sh"), "check", str(path), "cloudfront-local"]
            result = subprocess.run(command, env=process_env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(Path(process_env["AWS_TEST_LOG"]).exists())
            result = subprocess.run(command + ["--preview-without-oauth"], env=process_env,
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = Path(process_env["AWS_TEST_LOG"]).read_text().splitlines()
            self.assertEqual(3, len(commands))
            self.assertTrue(commands[0].startswith("network inspect"))
            self.assertTrue(commands[1].endswith("config --quiet"))
            self.assertEqual("info", commands[2])

    def test_preview_shell_flag_is_rejected_for_other_modes_and_unknown_stage(self):
        for mode, stage in [("tls", "--preview-without-oauth"), ("cloudfront", "--preview-without-oauth"),
                            ("cloudfront-local", "--pretend-production")]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                _, path, process_env = prepare_preview_runtime(directory)
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "up", str(path), mode, stage],
                                        env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(Path(process_env["AWS_TEST_LOG"]).exists())

    def make_curl(self, directory):
        path = directory / "curl"
        path.write_text("""#!/usr/bin/env python3
import os,sys
from urllib.parse import urlsplit
url=sys.argv[-1]
with open(os.environ["AWS_TEST_CURL_LOG"],"a") as log: log.write(urlsplit(url).path+"\\n")
route=urlsplit(url).path
if route in ["/login","/estate","/api/v2/listings"]: status="200"
elif route.startswith(("/actuator/","/api/internal/ai/")): status="404"
elif route=="/api/auth/kakao/code": status=os.environ.get("AWS_TEST_LOGIN_STATUS","503")
elif route=="/api/v2/me/units": status=os.environ.get("AWS_TEST_MANAGEMENT_STATUS","401")
else: raise SystemExit(99)
print(status,end="")
""")
        path.chmod(0o700)

    def test_preview_smoke_checks_login_unconfigured_and_management_authenticated(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, path, process_env = prepare_preview_runtime(directory)
            self.make_curl(directory)
            process_env["AWS_TEST_CURL_LOG"] = str(directory / "curl.log")
            result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "smoke", str(path),
                                     "cloudfront-local", "--preview-without-oauth"],
                                    env=process_env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Public-only preview passed", result.stdout)
            routes = Path(process_env["AWS_TEST_CURL_LOG"]).read_text().splitlines()
            self.assertEqual(7, len(routes))
            self.assertEqual(["/api/auth/kakao/code", "/api/v2/me/units"], routes[-2:])

    def test_preview_smoke_rejects_configured_login_or_open_management(self):
        for variable in ["AWS_TEST_LOGIN_STATUS", "AWS_TEST_MANAGEMENT_STATUS"]:
            with self.subTest(variable=variable), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                _, path, process_env = prepare_preview_runtime(directory)
                self.make_curl(directory)
                process_env["AWS_TEST_CURL_LOG"] = str(directory / "curl.log")
                process_env[variable] = "200"
                result = subprocess.run(["/bin/sh", str(ROOT / "deploy/aws.sh"), "smoke", str(path),
                                         "cloudfront-local", "--preview-without-oauth"],
                                        env=process_env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
