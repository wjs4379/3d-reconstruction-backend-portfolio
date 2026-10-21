#!/bin/sh
# Copy ONLY the existing MySQL public CA. No DB SQL, original volume mount, keys or restart.
set -eu
container=${1:?Usage: prepare-local-mysql-trust.sh CONTAINER ABSOLUTE_OUTPUT_DIRECTORY}
output=${2:?Usage: prepare-local-mysql-trust.sh CONTAINER ABSOLUTE_OUTPUT_DIRECTORY}
case "$container" in *[!A-Za-z0-9_.-]*|""|-*|.*) echo "Use the existing MySQL container name or ID" >&2; exit 1;; esac
case "$output" in /*) ;; *) echo "Use an absolute output directory" >&2; exit 1;; esac
command -v docker >/dev/null
mkdir -p "$output"
temporary=$(mktemp -d "$output/.ca-XXXXXX")
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
docker cp "$container":/var/lib/mysql/ca.pem "$temporary/ca.pem"
# MySQL 8.4 auto-generated public CA can have a single terminal NUL byte.
# Normalize only this known transport suffix in the temporary copy; never change
# the server CA/volume or accept embedded/repeated NULs or mixed PEM data.
python3 - "$temporary/ca.pem" <<'PYCA'
from pathlib import Path
import sys
p = Path(sys.argv[1])
data = p.read_bytes()
if data.endswith(b"\0"):
    data = data[:-1]
if b"\0" in data:
    raise SystemExit("Public CA contains an unexpected embedded or repeated NUL")
p.write_bytes(data)
PYCA
# Refuse a private key or mixed PEM content before producing any final output.
awk '
/^-----BEGIN CERTIFICATE-----$/ { if (inside) exit 1; inside=1; count++; next }
/^-----END CERTIFICATE-----$/ { if (!inside) exit 1; inside=0; next }
inside { if ($0 !~ /^[A-Za-z0-9+\/=]+$/) exit 1; next }
NF { exit 1 }
END { if (inside || count != 1) exit 1 }
' "$temporary/ca.pem"
docker run --rm --user "$(id -u):$(id -g)" -v "$temporary:/work" \
  eclipse-temurin:21-jdk-noble keytool -importcert -noprompt -storetype PKCS12 \
  -storepass changeit -alias existing-mysql-ca -file /work/ca.pem \
  -keystore /work/mysql-truststore.p12 >/dev/null
chmod 644 "$temporary/mysql-truststore.p12"
mv "$temporary/mysql-truststore.p12" "$output/mysql-truststore.p12"
cp "$temporary/ca.pem" "$output/mysql-ca.pem"
chmod 644 "$output/mysql-ca.pem"
echo "Existing MySQL public CA truststore prepared; DB and private keys were not changed."
