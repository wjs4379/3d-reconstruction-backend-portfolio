#!/usr/bin/env python3
"""Read dotenv without executing it; never print secret values."""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import stat
import sys
from urllib.parse import parse_qs, urlsplit

def read_env(path):
    result = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"Line {number}: expected KEY=value")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in result:
            raise ValueError(f"Line {number}: invalid or duplicate key")
        quoted = value.startswith(("'", '"'))
        if quoted:
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Line {number}: unmatched quoting")
            value = value[1:-1]
        if "$" in value or "\x00" in value or "\\" in value or (not quoted and re.search(r"\s#", value)):
            raise ValueError(f"Line {number}: use literal values without expansion, escapes or inline comments")
        result[key] = value
    return result

COMPOSE_MODES = {"tls": "compose.aws.yaml", "cloudfront": "compose.aws-cloudfront.yaml",
                 "cloudfront-local": "compose.aws-cloudfront-local.yaml"}

def compose_file(mode):
    if mode not in COMPOSE_MODES:
        raise ValueError("Deployment mode must be tls, cloudfront or cloudfront-local")
    return Path(__file__).resolve().parent.parent / COMPOSE_MODES[mode]

def validate_process_environment(env, mode="tls"):
    """Compose shell variables override --env-file; reject unvalidated substitutions."""
    compose = compose_file(mode)
    names = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)", compose.read_text(encoding="utf-8")))
    return [f"{key}: unset the exported shell variable; values must come from the checked environment file"
            for key in sorted(names) if key in os.environ and os.environ[key] != env.get(key)]


def private_ipv4(value):
    address = ipaddress.ip_address(value)
    networks = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    return address.version == 4 and (address.is_loopback or
           any(address in ipaddress.ip_network(network) for network in networks))


def validate(env, check_files=True, mode="tls", preview_without_oauth=False):
    compose_file(mode)  # Reject unknown modes; the default preserves direct-TLS checks.
    errors = []
    preview = preview_without_oauth and mode == "cloudfront-local"
    if preview_without_oauth and not preview:
        errors.append("PREVIEW_STAGE: only cloudfront-local supports --preview-without-oauth")
    oauth_keys = ["KAKAO_APP_ID", "KAKAO_REST_API_KEY", "KAKAO_CLIENT_SECRET",
                  "KAKAO_LOCAL_REST_API_KEY", "VITE_KAKAO_REST_API_KEY"]
    required = ["API_DOMAIN", "AWS_REGION", "DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "JWT_SECRET", "MEDIA_S3_BUCKET", "VITE_MAP_TILE_URL", "VITE_MAP_TILE_ATTRIBUTION"]
    if preview:
        for key in oauth_keys + ["GOOGLE_CLIENT_ID"]:
            if env.get(key, "") != "":
                errors.append(f"{key}: preview requires the whole OAuth/address configuration to be blank")
        if env.get("AI_PIPELINE_ENABLED", "false") != "false" or env.get("OUTBOX_WORKER_ENABLED", "false") != "false":
            errors.append("PREVIEW_STAGE: AI_PIPELINE_ENABLED and OUTBOX_WORKER_ENABLED must both be false")
    else:
        required += oauth_keys
    required += ["MYSQL_TRUST_DIRECTORY", "EXISTING_DB_NETWORK"] if mode == "cloudfront-local" else ["RDS_TRUST_DIRECTORY"]
    required += ["TLS_DIRECTORY", "ACME_WEBROOT"] if mode == "tls" else ["CLOUDFRONT_ORIGIN_SECRET"]
    for key in required:
        value = env.get(key, "")
        if not value or any(marker in value.lower() for marker in ("replace-with", "your-db", "your-domain", ".example")):
            errors.append(f"{key}: set the real value")
    if not re.fullmatch(r"(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}", env.get("API_DOMAIN", "")):
        errors.append("API_DOMAIN: use a DNS name without scheme/path/port")
    if mode in ("cloudfront", "cloudfront-local"):
        if not re.fullmatch(r"d[a-z0-9]{5,62}\.cloudfront\.net", env.get("API_DOMAIN", "")):
            errors.append("API_DOMAIN: use the actual default CloudFront distribution hostname")
        if not re.fullmatch(r"[0-9a-f]{64,128}", env.get("CLOUDFRONT_ORIGIN_SECRET", "")):
            errors.append("CLOUDFRONT_ORIGIN_SECRET: use 32-64 random bytes encoded as lowercase hexadecimal")
    if not re.fullmatch(r"[a-z]{2}-[a-z]+-\d", env.get("AWS_REGION", "")):
        errors.append("AWS_REGION: invalid region identifier")
    url = env.get("DB_URL", "")
    try:
        parsed = urlsplit(url.removeprefix("jdbc:"))
        params = parse_qs(parsed.query)
        if mode == "cloudfront-local":
            # Only the already-running same-host MySQL is reused. Never point at the legacy schema.
            if not url.startswith("jdbc:mysql://") or parsed.hostname != "mysql" or parsed.port not in (None, 3306):
                errors.append("DB_URL: use the existing same-host Docker DNS alias mysql on port 3306")
            if parsed.username or parsed.password or parsed.fragment or parsed.path != "/metaround_estate_v5":
                errors.append("DB_URL: credentials belong in separate fields; use only metaround_estate_v5")
            expected = {"sslMode": "VERIFY_CA", "trustCertificateKeyStoreUrl": "file:/etc/mysql/mysql-truststore.p12",
                        "trustCertificateKeyStoreType": "PKCS12", "trustCertificateKeyStorePassword": "changeit"}
            params = parse_qs(parsed.query, keep_blank_values=True)
            if any(params.get(k) != [v] for k, v in expected.items()):
                errors.append("DB_URL: verified TLS and the mounted existing-MySQL CA truststore are required")
            allowed = set(expected) | {"serverTimezone", "characterEncoding", "useUnicode"}
            if set(params) - allowed:
                errors.append("DB_URL: use only the documented same-host TLS connection parameters")
        else:
            if not url.startswith("jdbc:mysql://") or not parsed.hostname or not parsed.hostname.endswith(".rds.amazonaws.com"):
                errors.append("DB_URL: use the RDS endpoint DNS in a MySQL JDBC URL")
            if parsed.username or parsed.password or parsed.fragment or parsed.path != "/spatial_asset_db" or parsed.port not in (None, 3306):
                errors.append("DB_URL: credentials belong in separate fields; use spatial_asset_db")
            expected = {"sslMode": "VERIFY_IDENTITY", "trustCertificateKeyStoreUrl": "file:/etc/rds/rds-truststore.p12",
                        "trustCertificateKeyStoreType": "PKCS12", "trustCertificateKeyStorePassword": "changeit"}
            if any(params.get(k) != [v] for k, v in expected.items()):
                errors.append("DB_URL: verified TLS and the mounted PKCS12 RDS truststore are required")
    except (ValueError, TypeError):
        errors.append("DB_URL: malformed JDBC URL")
    if mode == "cloudfront-local":
        network = env.get("EXISTING_DB_NETWORK", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", network) or network in ("host", "none", "bridge"):
            errors.append("EXISTING_DB_NETWORK: use the already-existing custom local Docker bridge network")
    if env.get("DB_USERNAME", "").lower() in ("root", "admin", "master"):
        errors.append("DB_USERNAME: create a non-master application user")
    if len(env.get("DB_PASSWORD", "").encode()) < 16:
        errors.append("DB_PASSWORD: use at least 16 bytes")
    if len(env.get("JWT_SECRET", "").encode()) < 32:
        errors.append("JWT_SECRET: use at least 32 random bytes")
    if not preview and not env.get("KAKAO_APP_ID", "").isdigit():
        errors.append("KAKAO_APP_ID: use the numeric application ID")
    if env.get("KAKAO_REST_API_KEY") != env.get("VITE_KAKAO_REST_API_KEY"):
        errors.append("KAKAO_REST_API_KEY: must match the public frontend Kakao client identifier")
    bucket = env.get("MEDIA_S3_BUCKET", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) or ".." in bucket:
        errors.append("MEDIA_S3_BUCKET: invalid S3 bucket name")
    bind = env.get("APP_BIND_ADDRESS", "127.0.0.1")
    try:
        if not private_ipv4(bind):
            errors.append("APP_BIND_ADDRESS: use loopback or the actual private IPv4")
    except ValueError:
        errors.append("APP_BIND_ADDRESS: use an IPv4 address")
    enabled, worker = env.get("AI_PIPELINE_ENABLED", "false"), env.get("OUTBOX_WORKER_ENABLED", "false")
    if enabled not in ("true", "false") or worker not in ("true", "false"):
        errors.append("AI/dispatch switches: use true or false")
    if enabled == "true" and worker != "true":
        errors.append("OUTBOX_WORKER_ENABLED: enabling new AI analysis requires the dispatch worker")
    if enabled == "true" or worker == "true":
        if len(env.get("AI_SERVICE_KEY", "").encode()) < 32:
            errors.append("AI_SERVICE_KEY: use at least 32 bytes before enabling AI/dispatch")
        for key in ("AI_SERVER_BASE_URL", "APP_BACKEND_BASE_URL"):
            try:
                origin = urlsplit(env.get(key, ""))
                if origin.scheme not in ("http", "https") or not origin.hostname or origin.username or origin.password or origin.path or origin.query or origin.fragment:
                    errors.append(f"{key}: use a private origin without a path")
                elif origin.scheme == "http":
                    target = ipaddress.ip_address(origin.hostname)
                    if not private_ipv4(origin.hostname) or target.is_loopback:
                        errors.append(f"{key}: HTTP origins must use an RFC1918 private IPv4")
            except ValueError:
                errors.append(f"{key}: use an explicit private IP origin")
        try:
            backend = urlsplit(env.get("APP_BACKEND_BASE_URL", ""))
            if backend.scheme != "http" or backend.hostname != bind or backend.port != 8080:
                errors.append("APP_BACKEND_BASE_URL: use http://APP_BIND_ADDRESS:8080 for the private callback listener")
            if ipaddress.ip_address(bind).is_loopback:
                errors.append("APP_BIND_ADDRESS: set the EC2 private IP when connecting a separate GPU host")
        except ValueError:
            pass
    try:
        tile = urlsplit(env.get("VITE_MAP_TILE_URL", ""))
        if tile.scheme != "https" or not tile.hostname or tile.username or tile.password or any(
            placeholder not in env.get("VITE_MAP_TILE_URL", "") for placeholder in ("{z}", "{x}", "{y}")
        ):
            errors.append("VITE_MAP_TILE_URL: use an HTTPS tile URL with {z}/{x}/{y} placeholders")
    except ValueError:
        errors.append("VITE_MAP_TILE_URL: malformed tile URL")
    directories = (["MYSQL_TRUST_DIRECTORY"] if mode == "cloudfront-local" else ["RDS_TRUST_DIRECTORY"]) + (["TLS_DIRECTORY", "ACME_WEBROOT"] if mode == "tls" else [])
    for key in directories:
        if not Path(env.get(key, "")).is_absolute():
            errors.append(f"{key}: use an absolute host path")
    if check_files:
        if mode == "cloudfront-local":
            if not (Path(env.get("MYSQL_TRUST_DIRECTORY", ".")) / "mysql-truststore.p12").is_file():
                errors.append("MYSQL_TRUST_DIRECTORY: run prepare-local-mysql-trust.sh first")
        elif not (Path(env.get("RDS_TRUST_DIRECTORY", ".")) / "rds-truststore.p12").is_file():
            errors.append("RDS_TRUST_DIRECTORY: run prepare-rds-trust.sh first")
        if mode == "tls":
            tls = Path(env.get("TLS_DIRECTORY", ".")) / "live/metaround"
            if not (tls / "fullchain.pem").is_file() or not (tls / "privkey.pem").is_file():
                errors.append("TLS_DIRECTORY: run tls.sh issue first")
            if not Path(env.get("ACME_WEBROOT", ".")).is_dir():
                errors.append("ACME_WEBROOT: create the challenge directory first")
    return errors

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("--skip-files", action="store_true", help="Validate values before CA/TLS creation")
    parser.add_argument("--mode", choices=tuple(COMPOSE_MODES), default="tls",
                        help="tls: direct HTTPS; cloudfront: private RDS; cloudfront-local: existing same-host MySQL with verified TLS")
    parser.add_argument("--preview-without-oauth", action="store_true",
                        help="Explicit public-only cloudfront-local preview; all OAuth/address keys blank and AI off")
    args = parser.parse_args()
    try:
        if stat.S_IMODE(args.env_file.stat().st_mode) & 0o077:
            raise ValueError("Environment file permissions must be 600")
        env = read_env(args.env_file)
        errors = validate_process_environment(env, args.mode) + validate(env, check_files=not args.skip_files, mode=args.mode, preview_without_oauth=args.preview_without_oauth)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("AWS environment checks passed; secrets were not displayed.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
