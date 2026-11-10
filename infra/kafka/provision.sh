#!/usr/bin/env bash
set -euo pipefail
umask 077
bin=${KAFKA_HOME:-/opt/kafka}/bin
config=${1:-/run/kafka-admin/client.properties}
topology=$(dirname "$0")/topology.tsv
bus_topic=config.refresh.events
common=(--bootstrap-server kafka-1:9092,kafka-2:9092,kafka-3:9092 --command-config "$config")
fail() { echo "$1" >&2; exit 1; }
# Compose waits for all nodes. Bound CLI timeouts also make standalone failures finite.
for node in 1 2 3; do
  "$bin/kafka-broker-api-versions.sh" --bootstrap-server "kafka-$node:9092" \
    --command-config "$config" >/dev/null 2>&1 || fail "Kafka node $node is not ready"
done
while read -r topic; do
  "$bin/kafka-topics.sh" "${common[@]}" --create --if-not-exists --topic "$topic" \
    --partitions 3 --replication-factor 3 --config min.insync.replicas=2 \
    --config retention.ms=604800000 --config cleanup.policy=delete \
    --config unclean.leader.election.enable=false >/dev/null 2>&1 || fail "Cannot create topic $topic"
  description=$("$bin/kafka-topics.sh" "${common[@]}" --describe --topic "$topic" 2>/dev/null) \
    || fail "Cannot describe topic $topic"
  header=${description%%$'\n'*}
  [[ $header =~ PartitionCount:[[:space:]]*3[[:space:]] ]] \
    && [[ $header =~ ReplicationFactor:[[:space:]]*3[[:space:]] ]] \
    || fail "Topic $topic has incompatible partitions/replicas; nothing was deleted"
  settings=${header##*Configs:}
  settings=${settings//[[:space:]]/}
  for setting in min.insync.replicas=2 retention.ms=604800000 cleanup.policy=delete unclean.leader.election.enable=false; do
    [[ ,$settings, == *",$setting,"* ]] \
      || fail "Topic $topic has incompatible configuration; nothing was deleted"
  done
done < <(awk -v bus="$bus_topic" '!/^#/ && NF {print $1} END {print bus}' "$topology" | sort -u)
monitor_resources=()
while read -r topic producer consumer group; do
  [[ -z $topic || $topic == \#* ]] && continue
  monitor_resources+=(--topic "$topic" --group "$group")
  # Kafka's ACL set deduplicates identical --add requests; no broad --producer grants.
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$producer" \
    --operation Write --operation Describe --topic "$topic" --resource-pattern-type literal \
    >/dev/null 2>&1 || fail "Cannot provision producer ACL for $topic"
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$producer" \
    --operation IdempotentWrite --cluster >/dev/null 2>&1 || fail 'Cannot provision idempotence ACL'
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$consumer" \
    --operation Read --operation Describe --topic "$topic" --resource-pattern-type literal \
    >/dev/null 2>&1 || fail "Cannot provision consumer ACL for $topic"
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$consumer" \
    --operation Read --group "$group" --resource-pattern-type literal \
    >/dev/null 2>&1 || fail "Cannot provision group ACL for $group"
done < "$topology"
# Every replica consumes its own copy. Only Config Server can publish refreshes.
"$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal User:CN=config-server \
  --operation Write --topic "$bus_topic" --resource-pattern-type literal \
  >/dev/null 2>&1 || fail 'Cannot provision configuration publisher ACL'
for service in config-server wardrobe-service recommendation-service; do
  principal=$service
  if [[ $service == config-server ]]; then principal=CN=config-server; fi
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$principal" \
    --operation Read --operation Describe --topic "$bus_topic" --resource-pattern-type literal \
    >/dev/null 2>&1 || fail "Cannot provision configuration topic ACL for $service"
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal "User:$principal" \
    --operation Read --group "config-bus.$service." --resource-pattern-type prefixed \
    >/dev/null 2>&1 || fail "Cannot provision configuration group ACL for $service"
done
if (( ${#monitor_resources[@]} )); then
  "$bin/kafka-acls.sh" "${common[@]}" --add --allow-principal 'User:CN=kafka-monitor' \
    --operation Describe --resource-pattern-type literal "${monitor_resources[@]}" \
    >/dev/null 2>&1 || fail 'Cannot provision monitoring ACLs'
fi
echo 'Kafka topology and ACLs provisioned'
