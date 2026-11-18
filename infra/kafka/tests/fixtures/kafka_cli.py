#!/usr/bin/env python3
"""Sanitized Kafka CLI double: persistent topics/ACL set, never connects to a broker."""
import json
import os
from pathlib import Path
import sys


args = sys.argv[1:]
name = Path(sys.argv[0]).name
state_file = Path(os.environ["FAKE_STATE"])
state = json.loads(state_file.read_text()) if state_file.exists() else {"topics": {}, "acls": []}
with open(os.environ["FAKE_CALLS"], "a") as calls:
    calls.write(json.dumps([name, *args]) + "\n")
if os.environ.get("FAKE_FAIL") == name:
    print("SECRET_FROM_FAILED_COMMAND", file=sys.stderr)
    sys.exit(1)


def value(option):
    return args[args.index(option) + 1]


if name == "kafka-storage.sh":
    config = dict(line.split("=", 1) for line in Path(value("--config")).read_text().splitlines()
                  if line and not line.startswith("#"))
    directory = Path(config["log.dirs"])
    (directory / "meta.properties").write_text(
        f'cluster.id={value("--cluster-id")}\nnode.id={config["node.id"]}\n')
elif name == "kafka-server-start.sh":
    print("Kafka server fixture started")
elif name == "kafka-topics.sh":
    topic = value("--topic")
    if "--create" in args:
        configs = [args[i + 1] for i, arg in enumerate(args) if arg == "--config"]
        state["topics"].setdefault(topic, {"partitions": int(value("--partitions")),
                                         "replicas": int(value("--replication-factor")),
                                         "config": configs})
    if "--describe" in args:
        spec = state["topics"][topic]
        print(f'Topic: {topic}\tTopicId: fixture\tPartitionCount: {spec["partitions"]}'
              f'\tReplicationFactor: {spec["replicas"]}\tConfigs: {",".join(spec["config"])}')
        print(f'Topic: {topic}\tPartition: 0\tLeader: 1\tReplicas: 1,2,3\tIsr: 1,2,3')
elif name == "kafka-acls.sh":
    acl = args[args.index("--add") + 1:]
    if acl not in state["acls"]:
        state["acls"].append(acl)
state_file.write_text(json.dumps(state, sort_keys=True))
