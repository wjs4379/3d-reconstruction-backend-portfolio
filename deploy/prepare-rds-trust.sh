#!/bin/sh
# Public CA only; requires Docker/curl/awk, no AWS credentials.
set -eu
region=${1:?Usage: prepare-rds-trust.sh REGION ABSOLUTE_OUTPUT_DIRECTORY}
output=${2:?Usage: prepare-rds-trust.sh REGION ABSOLUTE_OUTPUT_DIRECTORY}
case "$region" in *[!a-z0-9-]*|"") echo "Invalid region" >&2; exit 1;; esac
case "$output" in /*) ;; *) echo "Use an absolute output path" >&2; exit 1;; esac
command -v docker >/dev/null
command -v curl >/dev/null
mkdir -p "$output"
temporary=$(mktemp -d "$output/.ca-XXXXXX")
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
curl --fail --silent --show-error --location --proto '=https' \
  "https://truststore.pki.rds.amazonaws.com/$region/$region-bundle.pem" -o "$temporary/bundle.pem"
awk -v destination="$temporary" '
/-----BEGIN CERTIFICATE-----/ { number++; file=sprintf("%s/ca-%d.pem",destination,number) }
file { print > file }
/-----END CERTIFICATE-----/ { close(file); file="" }
END { if (number == 0) exit 1 }
' "$temporary/bundle.pem"
docker run --rm --user "$(id -u):$(id -g)" -v "$temporary:/work" \
  eclipse-temurin:21-jdk-noble /bin/sh -ec '
  for certificate in /work/ca-*.pem; do
    alias=$(basename "$certificate" .pem)
    keytool -importcert -noprompt -storetype PKCS12 -storepass changeit \
      -keystore /work/rds-truststore.p12 -alias "$alias" -file "$certificate" >/dev/null
  done
  keytool -list -storetype PKCS12 -storepass changeit -keystore /work/rds-truststore.p12 >/dev/null
'
chmod 644 "$temporary/rds-truststore.p12"
mv "$temporary/rds-truststore.p12" "$output/rds-truststore.p12"
cp "$temporary/bundle.pem" "$output/rds-bundle.pem"
chmod 644 "$output/rds-bundle.pem"
echo "RDS CA truststore prepared (public certificates only)."
