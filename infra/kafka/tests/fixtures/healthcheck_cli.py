#!/usr/bin/env python3
"""Record healthcheck CLI calls without network access or real credentials."""
import json
import os
from pathlib import Path
import sys


name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["FAKE_CALLS"], "a") as calls:
    calls.write(json.dumps([name, *args]) + "\n")
if name == "timeout":
    os.execvp(args[3], args[3:])
elif name == "kcat":
    sys.exit(int(os.environ.get("FAKE_KCAT_STATUS", "0")))
else:
    sys.exit(99)
