import contextlib
import copy
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import threading
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("ai_teammate_probe", Path(__file__).resolve().parents[1] / "ai_teammate_probe.py")
probe = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = probe
SPEC.loader.exec_module(probe)

KEY = "offline-test-only-private-service-key-1234567890"
SENTINEL = "response-and-exception-data-must-not-escape"


class FixtureServer:
    def __init__(self):
        self.requests = []
        self.photos = [b"photo-content-" + str(index).encode() for index in range(4)]
        self.routes = {
            "/api/health": (200, "application/json", {"status": "ok"}),
            "/api/health/ready": (200, "application/json", {
                "status": "ready", "checks": {"worker": True, "model": True, "database": True}}),
            "/api/health/gpu": (200, "application/json", {
                "cudaAvailable": True, "deviceCount": 1, "devices": [{"index": 0, "name": "test-only"}]}),
            "/api/health/checkpoints": (200, "application/json", {
                "registration": {"strategy": "da3_footprint", "modelLoaded": True}}),
            "/actuator/health/readiness": (200, "application/json", {"status": "UP"}),
        }
        for index, body in enumerate(self.photos, 1):
            self.routes[f"/api/internal/ai/assets/{index}/content"] = (200, "image/jpeg", body)
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append({"method": self.command, "path": self.path,
                                       "key": self.headers.get("X-AI-Service-Key")})
                status, content_type, body = owner.routes.get(self.path, (404, "text/plain", b"missing"))
                body = json.dumps(body).encode() if isinstance(body, (dict, list)) else body
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                if status in (301, 302, 303, 307, 308):
                    self.send_header("Location", owner.redirect_to)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.origin = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(3)

    def run(self, **kwargs):
        return probe.probe(ai_origin=self.origin, backend_origin=self.origin, key=KEY, **kwargs)

    def photo_arguments(self):
        return {"asset_ids": [1, 2, 3, 4],
                "expected_hashes": [hashlib.sha256(body).hexdigest() for body in self.photos]}


class TeammateProbeTests(unittest.TestCase):
    def test_verified_four_photo_read_is_get_only_and_never_claims_post_or_inference(self):
        with FixtureServer() as fixture:
            result = fixture.run(**fixture.photo_arguments())
            self.assertTrue(result["success"])
            self.assertTrue(result["sourcePhotosVerified"])
            self.assertEqual(4, result["photosVerified"])
            self.assertFalse(result["callbackPostVerified"])
            self.assertFalse(result["endToEndVerified"])
            self.assertTrue(all(request["method"] == "GET" for request in fixture.requests))
            for request in fixture.requests:
                self.assertNotIn(KEY, request["path"])
                self.assertNotIn("?", request["path"])
                if request["path"] in ("/api/health", "/actuator/health/readiness"):
                    self.assertIsNone(request["key"])
                else:
                    self.assertEqual(KEY, request["key"])
            self.assertNotIn(KEY, json.dumps(result))

    def test_without_photos_success_means_only_basic_read_checks(self):
        with FixtureServer() as fixture:
            result = fixture.run()
            self.assertTrue(result["success"])
            self.assertEqual(0, result["photosRequested"])
            self.assertFalse(result["sourcePhotosVerified"])
            self.assertFalse(result["endToEndVerified"])

    def test_readiness_requires_literal_true_for_each_check(self):
        with FixtureServer() as fixture:
            for field in ("worker", "model", "database"):
                for invalid in (1, "true", None, False):
                    with self.subTest(field=field, invalid=invalid):
                        checks = {"worker": True, "model": True, "database": True, field: invalid}
                        fixture.routes["/api/health/ready"] = (200, "application/json", {"status": "ready", "checks": checks})
                        self.assertFalse(fixture.run()["success"])

    def test_ready_json_on_non_success_status_does_not_count_as_ready(self):
        with FixtureServer() as fixture:
            status, content_type, body = fixture.routes["/api/health/ready"]
            fixture.routes["/api/health/ready"] = (503, content_type, body)
            result = fixture.run()
            self.assertFalse(result["aiReady"])
            self.assertFalse(result["success"])
            self.assertEqual(503, result["statusCodes"]["readiness"])

    def test_mock_strategy_never_counts_as_actual_ai(self):
        with FixtureServer() as fixture:
            fixture.routes["/api/health/checkpoints"] = (200, "application/json", {
                "registration": {"strategy": "mock", "modelLoaded": True}})
            result = fixture.run()
            self.assertTrue(result["aiReady"])
            self.assertEqual("mock", result["registrationStrategy"])
            self.assertFalse(result["registrationReady"])
            self.assertFalse(result["success"])

    def test_unrecognized_or_malformed_registration_does_not_escape_into_output(self):
        with FixtureServer() as fixture:
            for invalid in (SENTINEL, ["da3_footprint"], {"private": SENTINEL}, None):
                with self.subTest(kind=type(invalid).__name__):
                    fixture.routes["/api/health/checkpoints"] = (200, "application/json", {
                        "registration": {"strategy": invalid, "modelLoaded": True}})
                    result = fixture.run()
                    self.assertFalse(result["success"])
                    self.assertEqual("unknown", result["registrationStrategy"])
                    self.assertNotIn(SENTINEL, json.dumps(result))

    def test_gpu_requires_actual_cuda_boolean_and_positive_integer_device_count(self):
        with FixtureServer() as fixture:
            invalid_values = [
                {"cudaAvailable": "true", "deviceCount": 1, "devices": [{}]},
                {"cudaAvailable": True, "deviceCount": True, "devices": [{}]},
                {"cudaAvailable": True, "deviceCount": 0, "devices": []},
                {"cudaAvailable": True, "deviceCount": 1, "devices": []},
            ]
            for invalid in invalid_values:
                fixture.routes["/api/health/gpu"] = (200, "application/json", invalid)
                self.assertFalse(fixture.run()["gpuReady"])

    def test_redirect_never_reaches_a_second_origin_or_forwards_key(self):
        with FixtureServer() as sink, FixtureServer() as fixture:
            fixture.redirect_to = sink.origin + "/stolen?key=" + KEY
            fixture.routes["/api/health/ready"] = (302, "text/plain", SENTINEL.encode())
            result = fixture.run()
            self.assertFalse(result["success"])
            self.assertEqual([], sink.requests)
            self.assertIn("READINESS_REDIRECT_REJECTED", result["errors"])
            self.assertNotIn(KEY, json.dumps(result))
            self.assertNotIn(SENTINEL, json.dumps(result))

    def test_ambient_proxy_is_ignored_for_internal_key_requests(self):
        with FixtureServer() as proxy, FixtureServer() as fixture:
            environment = {"HTTP_PROXY": proxy.origin, "http_proxy": proxy.origin,
                           "HTTPS_PROXY": proxy.origin, "NO_PROXY": "", "no_proxy": ""}
            with mock.patch.dict(os.environ, environment):
                self.assertTrue(fixture.run()["success"])
            self.assertEqual([], proxy.requests)

    def test_oversized_json_response_is_bounded_and_not_output(self):
        with FixtureServer() as fixture:
            fixture.routes["/api/health/checkpoints"] = (200, "application/json", b"x" * (probe.JSON_LIMIT + 1))
            result = fixture.run()
            self.assertFalse(result["success"])
            self.assertIn("CHECKPOINTS_RESPONSE_TOO_LARGE", result["errors"])

    def test_photo_wrong_sha_content_type_empty_or_oversized_body_cannot_verify(self):
        with FixtureServer() as fixture:
            original = fixture.routes["/api/internal/ai/assets/1/content"]
            for invalid in [(200, "image/jpeg", b"wrong-photo"), (200, "text/plain", original[2]),
                            (200, "image/jpeg", b""), (200, "image/jpeg", b"x" * (probe.PHOTO_LIMIT + 1))]:
                fixture.routes["/api/internal/ai/assets/1/content"] = invalid
                result = fixture.run(**fixture.photo_arguments())
                self.assertFalse(result["success"])
                self.assertFalse(result["sourcePhotosVerified"])
                self.assertEqual(3, result["photosVerified"])

    def test_four_unique_ids_and_four_unique_hashes_are_required_before_network(self):
        valid_hashes = [hashlib.sha256(str(index).encode()).hexdigest() for index in range(4)]
        invalid = [([1], valid_hashes[:1]), ([1, 1, 2, 3], valid_hashes),
                   ([1, 2, 3, 4], [valid_hashes[0]] * 4), ([1, 2, 3, -4], valid_hashes),
                   ([1, 2, 3, 4], [SENTINEL] * 4), ([], valid_hashes)]
        with mock.patch.object(probe.urllib.request, "build_opener") as opener:
            for ids, hashes in invalid:
                with self.assertRaises(probe.ProbeError):
                    probe.probe(ai_origin="http://127.0.0.1:8000", backend_origin="http://127.0.0.1:8083",
                                key=KEY, asset_ids=ids, expected_hashes=hashes)
            opener.assert_not_called()

    def test_loopback_origin_rejects_userinfo_non_loopback_path_query_and_bad_ports(self):
        invalid = ["https://127.0.0.1:8000", "http://0.0.0.0:8000", "http://192.168.1.2:8000",
                   "http://user:" + KEY + "@127.0.0.1:8000", "http://127.0.0.1:8000/api",
                   "http://127.0.0.1:8000/", "http://127.0.0.1:8000?x=1", "http://127.0.0.1:8000#",
                   "http://127.0.0.1", "http://127.0.0.1:80", " http://127.0.0.1:8000"]
        for origin in invalid:
            with self.subTest(origin=origin.split("@")[0] if "@" not in origin else "userinfo"):
                with self.assertRaises(probe.ProbeError):
                    probe.loopback_origin(origin)
        for valid in ("http://localhost:8000", "http://127.0.0.1:8083", "http://[::1]:8000"):
            self.assertEqual(valid, probe.loopback_origin(valid))

    def test_invalid_or_header_injecting_key_is_rejected_before_network(self):
        with mock.patch.object(probe.urllib.request, "build_opener") as opener:
            for key in ("", "short", KEY + "\r\nInjected: yes", "x" * 513, "가" * 32):
                with self.assertRaises(probe.ProbeError):
                    probe.probe(ai_origin="http://127.0.0.1:8000", backend_origin="http://127.0.0.1:8083", key=key)
            opener.assert_not_called()

    def test_argparse_errors_and_bad_env_never_echo_sensitive_values(self):
        for arguments, environment in [
            (["--key", KEY], {}),
            (["--asset-id", KEY], {}),
            ([], {"AI_BACKEND_BASE_URL": "http://user:" + KEY + "@127.0.0.1:8083", "AI_SERVICE_KEY": KEY}),
        ]:
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, probe.main(arguments, environment))
            self.assertNotIn(KEY, stdout.getvalue())
            self.assertEqual("", stderr.getvalue())
            self.assertFalse(json.loads(stdout.getvalue())["success"])

    def test_reflected_error_bodies_and_network_exception_text_are_not_printed(self):
        with FixtureServer() as fixture:
            fixture.routes["/api/health/ready"] = (401, "application/json", {"detail": KEY + SENTINEL})
            report = fixture.run()
            self.assertNotIn(KEY, json.dumps(report))
            self.assertNotIn(SENTINEL, json.dumps(report))
        opener = mock.Mock()
        opener.open.side_effect = RuntimeError(KEY + SENTINEL)
        report = probe.probe(ai_origin="http://127.0.0.1:8000", backend_origin="http://127.0.0.1:8083", key=KEY, opener=opener)
        self.assertFalse(report["success"])
        self.assertNotIn(KEY, json.dumps(report))
        self.assertNotIn(SENTINEL, json.dumps(report))

    def test_ready_requires_correct_status_not_only_true_component_checks(self):
        with FixtureServer() as fixture:
            fixture.routes["/api/health/ready"][2]["status"] = "not_ready"
            self.assertFalse(fixture.run()["aiReady"])

    def test_malformed_json_and_content_types_fail_without_reflecting_payload(self):
        with FixtureServer() as fixture:
            for content_type, body in [("application/json", b"not-json-" + SENTINEL.encode()),
                                       ("text/html", SENTINEL.encode()), ("application/json", [])]:
                fixture.routes["/api/health/ready"] = (200, content_type, body)
                result = fixture.run()
                self.assertFalse(result["success"])
                self.assertNotIn(SENTINEL, json.dumps(result))


if __name__ == "__main__":
    unittest.main()
