#!/usr/bin/env python3
"""GET-only checks run by the AI owner on their own computer.

Read AI_SERVICE_KEY and AI_BACKEND_BASE_URL from that owner's private environment.
The AI origin is --ai-origin, AI_LOCAL_BASE_URL, or http://127.0.0.1:8000.
All origins must be explicit HTTP loopback origins. A reverse SSH listener may
provide the backend origin. This tool neither creates that connection nor copies
keys, photographs, source files or configuration to another computer.

Optional source verification requires four --asset-id and four
--expected-sha256 arguments in matching order, supplied after the backend owner
has confirmed the photographs. It only reads those existing backend assets.
Successful GET checks do not verify a callback POST or an end-to-end inference.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import ipaddress
import json
import os
import re
import sys
from typing import Mapping
import urllib.error
import urllib.parse
import urllib.request


JSON_LIMIT = 256 * 1024
PHOTO_LIMIT = 5 * 1024 * 1024
REAL_STRATEGIES = frozenset({"vggt", "hybrid_consensus", "hybrid_mastr", "da3_footprint"})
IMAGE_TYPES = frozenset({"image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"})


class ProbeError(Exception):
    """Only fixed, non-sensitive error codes may leave the probe."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally repeats input values, which may contain pasted keys.
        raise ProbeError("INVALID_ARGUMENTS")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(frozen=True)
class ReadResult:
    status: int = 0
    body: bytes = b""
    content_type: str = ""
    error: str = ""


def loopback_origin(value: str) -> str:
    try:
        if not isinstance(value, str) or not value or value != value.strip():
            raise ValueError
        if any(ord(char) < 33 or ord(char) == 127 for char in value) or "?" in value or "#" in value:
            raise ValueError
        parsed = urllib.parse.urlsplit(value)
        host = parsed.hostname
        if (parsed.scheme != "http" or not host or parsed.username is not None
                or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
                or parsed.port is None or not 1024 <= parsed.port <= 65535):
            raise ValueError
        if host != "localhost" and not ipaddress.ip_address(host).is_loopback:
            raise ValueError
        return value
    except (TypeError, ValueError):
        raise ProbeError("INVALID_LOOPBACK_ORIGIN") from None


def service_key(value: str) -> str:
    if (not isinstance(value, str) or not 32 <= len(value.encode("utf-8")) <= 512
            or not value.isascii() or any(ord(char) < 33 or ord(char) > 126 for char in value)):
        raise ProbeError("INVALID_SERVICE_KEY")
    return value


def source_pairs(asset_ids, expected_hashes):
    if not asset_ids and not expected_hashes:
        return []
    if (len(asset_ids) != 4 or len(expected_hashes) != 4
            or any(type(value) is not int or value <= 0 for value in asset_ids)
            or len(set(asset_ids)) != 4
            or any(not re.fullmatch(r"[0-9a-fA-F]{64}", value) for value in expected_hashes)
            or len({value.lower() for value in expected_hashes}) != 4):
        raise ProbeError("INVALID_FOUR_PHOTO_INPUT")
    return list(zip(asset_ids, (value.lower() for value in expected_hashes)))


def read_get(opener, origin: str, path: str, key: str | None = None,
             limit: int = JSON_LIMIT) -> ReadResult:
    # All paths are internal constants or positive integer asset IDs.
    headers = {"Accept": "application/json" if limit == JSON_LIMIT else "image/*"}
    if key is not None:
        headers["X-AI-Service-Key"] = key
    request = urllib.request.Request(origin + path, headers=headers, method="GET")
    try:
        with opener.open(request, timeout=5) as response:
            body = response.read(limit + 1)
            status = response.status
            content_type = response.headers.get_content_type()
            if len(body) > limit:
                return ReadResult(status=status, error="RESPONSE_TOO_LARGE")
            return ReadResult(status=status, body=body, content_type=content_type)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        return ReadResult(status=status, error="REDIRECT_REJECTED" if 300 <= status < 400 else "HTTP_ERROR")
    except Exception:
        # Network/URL exceptions can include hosts, keys or reflected response data.
        return ReadResult(error="REQUEST_FAILED")


def object_json(response: ReadResult):
    if response.status != 200 or response.error or response.content_type != "application/json":
        return {}
    try:
        value = json.loads(response.body)
        return value if isinstance(value, dict) else {}
    except (ValueError, UnicodeError):
        return {}


def blank_report():
    return {
        "schemaVersion": 1,
        "success": False,
        "aiLiveness": False,
        "aiReady": False,
        "aiWorkerReady": False,
        "aiModelReady": False,
        "aiDatabaseReady": False,
        "gpuReady": False,
        "registrationReady": False,
        "registrationStrategy": "unknown",
        "backendReady": False,
        "photosRequested": 0,
        "photosVerified": 0,
        "sourcePhotosVerified": False,
        "callbackPostVerified": False,
        "endToEndVerified": False,
        "statusCodes": {},
        "errors": [],
    }


def probe(*, ai_origin: str, backend_origin: str, key: str,
          asset_ids=(), expected_hashes=(), opener=None):
    report = blank_report()
    ai_origin = loopback_origin(ai_origin)
    backend_origin = loopback_origin(backend_origin)
    key = service_key(key)
    photos = source_pairs(asset_ids, expected_hashes)
    report["photosRequested"] = len(photos)
    if opener is None:
        # Never send the internal key through ambient HTTP(S)_PROXY settings.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def get(label, origin, path, auth=False, limit=JSON_LIMIT):
        response = read_get(opener, origin, path, key if auth else None, limit)
        report["statusCodes"][label] = response.status
        if response.error:
            report["errors"].append(label.upper() + "_" + response.error)
        return response

    live = object_json(get("liveness", ai_origin, "/api/health"))
    report["aiLiveness"] = live.get("status") == "ok"
    ready = object_json(get("readiness", ai_origin, "/api/health/ready", True))
    checks = ready.get("checks") if isinstance(ready.get("checks"), dict) else {}
    for field, check in (("aiWorkerReady", "worker"), ("aiModelReady", "model"), ("aiDatabaseReady", "database")):
        report[field] = checks.get(check) is True
    report["aiReady"] = ready.get("status") == "ready" and all(
        report[field] for field in ("aiWorkerReady", "aiModelReady", "aiDatabaseReady"))

    gpu = object_json(get("gpu", ai_origin, "/api/health/gpu", True))
    count = gpu.get("deviceCount")
    devices = gpu.get("devices")
    report["gpuReady"] = (gpu.get("cudaAvailable") is True and type(count) is int
                          and count > 0 and isinstance(devices, list) and len(devices) >= count
                          and all(isinstance(device, dict) for device in devices[:count]))
    checkpoints = object_json(get("checkpoints", ai_origin, "/api/health/checkpoints", True))
    registration = checkpoints.get("registration")
    registration = registration if isinstance(registration, dict) else {}
    strategy = registration.get("strategy")
    if isinstance(strategy, str) and strategy in REAL_STRATEGIES | {"mock"}:
        report["registrationStrategy"] = strategy
    report["registrationReady"] = (registration.get("modelLoaded") is True
                                   and isinstance(strategy, str) and strategy in REAL_STRATEGIES)

    backend = object_json(get("backend", backend_origin, "/actuator/health/readiness"))
    report["backendReady"] = backend.get("status") == "UP"

    for index, (asset_id, expected) in enumerate(photos):
        response = get("photo" + str(index + 1), backend_origin,
                       "/api/internal/ai/assets/" + str(asset_id) + "/content", True, PHOTO_LIMIT)
        valid = (response.status == 200 and not response.error and bool(response.body)
                 and response.content_type in IMAGE_TYPES
                 and hashlib.sha256(response.body).hexdigest() == expected)
        if valid:
            report["photosVerified"] += 1
        else:
            report["errors"].append("PHOTO" + str(index + 1) + "_VERIFICATION_FAILED")
    report["sourcePhotosVerified"] = len(photos) == 4 and report["photosVerified"] == 4

    checks_passed = all(report[field] for field in (
        "aiLiveness", "aiReady", "gpuReady", "registrationReady", "backendReady"))
    report["success"] = checks_passed and (not photos or report["sourcePhotosVerified"])
    if not report["success"] and not report["errors"]:
        report["errors"].append("CHECKS_NOT_READY")
    return report


def main(argv=None, environ: Mapping[str, str] | None = None):
    environment = os.environ if environ is None else environ
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("--ai-origin", default=environment.get("AI_LOCAL_BASE_URL", "http://127.0.0.1:8000"))
    parser.add_argument("--asset-id", action="append", type=int, default=[])
    parser.add_argument("--expected-sha256", action="append", default=[])
    try:
        arguments = parser.parse_args(argv)
        report = probe(ai_origin=arguments.ai_origin,
                       backend_origin=environment.get("AI_BACKEND_BASE_URL", ""),
                       key=environment.get("AI_SERVICE_KEY", ""),
                       asset_ids=arguments.asset_id, expected_hashes=arguments.expected_sha256)
    except ProbeError as error:
        report = blank_report()
        report["errors"] = [str(error)]
    except Exception:
        report = blank_report()
        report["errors"] = ["PROBE_FAILED"]
    print(json.dumps(report, separators=(",", ":"), sort_keys=True))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
