#!/bin/sh
# No AWS resources are created here. Only explicitly requested app deployment changes the host.
set -eu
task=${1:-check}
env_file=${2:-/etc/metaround/aws.env}
mode=${3:-tls}
preview=${4:-}
case "$preview" in
  "") ;;
  --preview-without-oauth)
    test "$mode" = cloudfront-local || { echo "Preview without OAuth is available only for cloudfront-local" >&2; exit 1; }
    ;;
  *) echo "Unknown deployment stage argument" >&2; exit 1;;
esac
case "$mode" in
  tls) compose_file=compose.aws.yaml;;
  cloudfront) compose_file=compose.aws-cloudfront.yaml;;
  cloudfront-local) compose_file=compose.aws-cloudfront-local.yaml;;
  *) echo "Deployment mode must be tls, cloudfront or cloudfront-local" >&2; exit 1;;
esac
repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "$env_file" in /*) ;; *) echo "Use an absolute environment file path" >&2; exit 1;; esac
cd "$repository"
command -v docker >/dev/null || { echo "Docker + Compose v2 are required" >&2; exit 1; }
compose() { docker compose --project-name metaround-aws --env-file "$env_file" -f "$compose_file" "$@"; }
validate_env() {
  if [ "$preview" = --preview-without-oauth ]; then
    python3 deploy/check-aws-env.py "$env_file" --mode "$mode" --preview-without-oauth
  else
    python3 deploy/check-aws-env.py "$env_file" --mode "$mode"
  fi
}
check_local_network() {
  if [ "$mode" != cloudfront-local ]; then return; fi
  network=$(python3 - "$env_file" <<'PYNET'
import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("aws_env", "deploy/check-aws-env.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print(module.read_env(Path(sys.argv[1]))["EXISTING_DB_NETWORK"])
PYNET
)
  # Read-only inspection. Never create a network, DB container, schema or mount the old DB volume.
  driver_scope=$(docker network inspect --format '{{.Driver}} {{.Scope}}' "$network")
  test "$driver_scope" = "bridge local" || { echo "EXISTING_DB_NETWORK must be an existing same-host Docker bridge" >&2; exit 1; }
}
case "$task" in
  check)
    validate_env
    check_local_network
    compose config --quiet
    docker info >/dev/null
    echo "Configuration ready. No deployment was performed."
    ;;
  up)
    validate_env
    check_local_network
    compose config --quiet
    # Existing non-baselined databases fail safely instead of silently changing schema history.
    compose up -d --build --wait --wait-timeout 180
    compose exec -T nginx nginx -t
    # Existing nginx workers cache upstream container addresses across app/web recreation.
    compose exec -T nginx nginx -s reload
    echo "Application started. Run aws.sh smoke and verify real OAuth/S3 in the browser."
    ;;
  status)
    compose ps
    ;;
  smoke)
    validate_env
    domain=$(python3 - "$env_file" <<'PY'
import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("aws_env", "deploy/check-aws-env.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print(module.read_env(Path(sys.argv[1]))["API_DOMAIN"])
PY
)
    origin="https://$domain"
    for path in /login /estate /api/v2/listings; do
      code=$(curl --silent --show-error --connect-timeout 5 --max-time 30 -o /dev/null -w '%{http_code}' "$origin$path")
      test "$code" = 200 || { echo "$path returned $code" >&2; exit 1; }
    done
    for path in /actuator/health/readiness /api/internal/ai/webhook; do
      code=$(curl --silent --show-error --connect-timeout 5 --max-time 30 -o /dev/null -w '%{http_code}' "$origin$path")
      test "$code" = 404 || { echo "Internal route $path is exposed ($code)" >&2; exit 1; }
    done
    if [ "$preview" = --preview-without-oauth ]; then
      code=$(curl --silent --show-error --connect-timeout 5 --max-time 30 -o /dev/null -w '%{http_code}' \
        -X POST -H 'Content-Type: application/json' --data '{"code":"preview-readiness-probe"}' "$origin/api/auth/kakao/code")
      test "$code" = 503 || { echo "Preview login must remain unconfigured ($code)" >&2; exit 1; }
      code=$(curl --silent --show-error --connect-timeout 5 --max-time 30 -o /dev/null -w '%{http_code}' "$origin/api/v2/me/units")
      test "$code" = 401 || { echo "Preview management must require real authentication ($code)" >&2; exit 1; }
      echo "Public-only preview passed; OAuth/address/AI remain unavailable and management is authenticated."
    fi
    echo "Public page/API and internal-route isolation checks passed."
    ;;
  *) echo "Usage: aws.sh {check|up|status|smoke} /absolute/aws.env [tls|cloudfront|cloudfront-local] [--preview-without-oauth]" >&2; exit 1;;
esac
