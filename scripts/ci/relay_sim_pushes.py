#!/usr/bin/env python3
"""CI only: delivers the dev backend's recorded iOS pushes to the booted Simulator.

There is no real APNs in CI. The dev backend records every push it would have sent
(GET /dev/pushes?after=<seq>, with the exact APNs payload for iOS devices); this relay feeds
each new one to the Simulator with `xcrun simctl push`, so the app's real notification and
background-update handling runs. Not evidence of APNs delivery or timing on a phone.

Usage: relay_sim_pushes.py <backend-url> <bundle-id> <log-file>
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

backend, bundle, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
after = -1
with open(log_path, "a", buffering=1) as log:
    log.write(f"relay started for {bundle}\n")
    while True:
        try:
            with urllib.request.urlopen(f"{backend}/dev/pushes?after={after}", timeout=5) as r:
                pushes = json.load(r)
        except Exception as e:  # backend not up yet, or restarting
            time.sleep(1)
            continue
        for p in pushes:
            after = max(after, p["seq"])
            if p.get("platform") != "ios" or "apns" not in p:
                continue
            payload = dict(p["apns"])
            payload["Simulator Target Bundle"] = bundle
            fd, path = tempfile.mkstemp(suffix=".apns")
            with os.fdopen(fd, "w") as f:
                json.dump(payload, f)
            res = subprocess.run(["xcrun", "simctl", "push", "booted", bundle, path], capture_output=True, text=True)
            os.unlink(path)
            kind = p.get("data", {}).get("t", "?")
            log.write(f"seq={p['seq']} t={kind} id={p.get('data', {}).get('id', '')} exit={res.returncode} {res.stderr.strip()}\n")
        time.sleep(1)
