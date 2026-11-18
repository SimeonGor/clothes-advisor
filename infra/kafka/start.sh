#!/usr/bin/env bash
set -euo pipefail
umask 077
credentials=${1:?credentials directory required}
data=${2:?data directory required}
node=${3:?node ID required}
bin=${KAFKA_HOME:-/opt/kafka}/bin
fail() { echo "$1" >&2; exit 1; }
[[ $(cat "$credentials/node.id") == "$node" ]] || fail 'Credential node ID mismatch'
cluster=$(cat "$credentials/cluster.id")
[[ $cluster =~ ^[A-Za-z0-9_-]{22}$ ]] || fail 'Invalid cluster ID'
mkdir -p "$data"
config=$(mktemp)
trap 'rm -f "$config"' EXIT
cat "$credentials/server.properties" > "$config"
printf '\nlog.dirs=%s\n' "$data" >> "$config"
if [[ -f $data/meta.properties ]]; then
  grep -qxF "cluster.id=$cluster" "$data/meta.properties" || fail 'Storage cluster ID mismatch'
  grep -qxF "node.id=$node" "$data/meta.properties" || fail 'Storage node ID mismatch'
else
  [[ -z $(ls -A "$data") ]] || fail 'Nonempty unformatted storage; refusing to modify it'
  args=()
  while IFS= read -r credential; do
    [[ -n $credential ]] && args+=(--add-scram "$credential")
  done < "$credentials/scram.txt"
  KAFKA_HEAP_OPTS='-Xms64m -Xmx256m' "$bin/kafka-storage.sh" format --cluster-id "$cluster" --config "$config" "${args[@]}" \
    >/dev/null 2>&1 || fail 'Kafka storage format failed; inspect private storage, do not reset it'
fi
# Kafka types PEM/JAAS configuration as Password and redacts those values in its logs.
echo "Starting Kafka node $node"
"$bin/kafka-server-start.sh" "$config" &
pid=$!
trap 'kill -TERM "$pid" 2>/dev/null || true' TERM INT
set +e
wait "$pid"
status=$?
if kill -0 "$pid" 2>/dev/null; then wait "$pid"; status=$?; fi
rm -f "$config"
[[ $status == 0 ]] || echo "Kafka exited; inspect container logs" >&2
exit "$status"
