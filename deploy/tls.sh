#!/bin/sh
# Run on EC2 after DNS points at it. Initial issue requires free port 80.
set -eu
task=${1:?Usage: tls.sh ACTION DOMAIN EMAIL TLS_DIRECTORY ACME_WEBROOT [ENV_FILE]}
domain=${2:?Set the real DNS domain}
email=${3:?Set the certificate contact email}
configuration=${4:?Set the absolute certbot configuration directory}
webroot=${5:?Set the absolute ACME webroot}
env_file=${6:-/etc/metaround/aws.env}
case "$domain" in *[!A-Za-z0-9.-]*|"") echo "Invalid domain" >&2; exit 1;; esac
case "$configuration" in /*) ;; *) echo "Use an absolute TLS directory" >&2; exit 1;; esac
case "$webroot" in /*) ;; *) echo "Use an absolute webroot" >&2; exit 1;; esac
mkdir -p "$configuration" "$webroot"
case "$task" in
  issue)
    # Never stop a running proxy automatically.
    docker run --rm -p 80:80 -v "$configuration:/etc/letsencrypt" \
      certbot/certbot:latest certonly --standalone --non-interactive \
      --agree-tos --email "$email" --cert-name metaround -d "$domain"
    ;;
  renew|test-renewal)
    dry_run=
    if [ "$task" = test-renewal ]; then dry_run=--dry-run; fi
    docker run --rm -v "$configuration:/etc/letsencrypt" -v "$webroot:/var/www/acme" \
      certbot/certbot:latest renew --non-interactive --webroot -w /var/www/acme $dry_run
    if [ "$task" = renew ]; then
      repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
      docker compose --project-name metaround-aws --env-file "$env_file" -f "$repository/compose.aws.yaml" exec -T nginx nginx -s reload
    fi
    ;;
  *) echo "Usage: tls.sh {issue|renew|test-renewal} DOMAIN EMAIL TLS_DIRECTORY ACME_WEBROOT [ENV_FILE]" >&2; exit 1;;
esac
