#!/usr/bin/env bash
set -euo pipefail
credentials=${1:-/run/kafka-node}
ca=''
# Reuse the public CA from the immutable Java client config; never forward its private key.
while IFS= read -r line; do
  case "$line" in
    ssl.truststore.certificates=*)
      printf -v ca '%b' "${line#ssl.truststore.certificates=}"
      break
      ;;
  esac
done < "$credentials/client.properties"
[[ -n $ca ]] || { echo 'Missing Kafka healthcheck CA' >&2; exit 1; }

# -m bounds the Metadata request; timeout also bounds client teardown.
exec timeout -s KILL 15 kcat -b "${HOSTNAME}:9094" -L -m 10 \
  -X security.protocol=ssl \
  -X "ssl.ca.pem=$ca" \
  -X "ssl.certificate.location=$credentials/certificate.pem" \
  -X "ssl.key.location=$credentials/key.pem" \
  -X enable.ssl.certificate.verification=true \
  -X ssl.endpoint.identification.algorithm=https \
  -X allow.auto.create.topics=false
