#!/usr/bin/env python3
"""
otm — OTel Trace Migration scanner
===================================
Scans APM trace data for span attributes, producing a report that can be
used to verify the APM agent → OTel SDK migration preserves all attributes
that downstream tooling depends on.

Usage:
    ./otm <environment> <verb> [args...]

Verbs:
    report                       Live scan via API keys (requires direct ES access)
    report --from-file <file>    Process a pre-exported _field_caps JSON response
    add-key <url> <key>          Store an ES API key for the given cluster URL
    list-keys                    List stored cluster URLs

The --from-file mode is the recommended approach when direct ES access is
not available (e.g. the ES endpoint is behind a Kibana proxy that requires
browser session auth).  Run this in the Kibana Dev Console and save the output:

    GET *:traces-apm*,traces-apm*/_field_caps?include_empty_fields=false

Then run:
    ./otm qa report --from-file field_caps.json

Environments are defined in config.yaml (e.g. 'qa', 'prod').
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.yaml"


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        print(f"ERROR: config.yaml not found at {CONFIG_FILE}", file=sys.stderr)
        sys.exit(1)
    with open(CONFIG_FILE) as f:
        return yaml.safe_load(f)


def usage() -> None:
    print(__doc__)
    sys.exit(1)


def main() -> None:
    args = sys.argv[1:]
    if len(args) < 2:
        usage()

    env, verb, *rest = args
    config = load_config()

    if verb == "report":
        if rest and rest[0] == "--from-file":
            if len(rest) < 2:
                print("Usage: ./otm <env> report --from-file <field_caps.json>", file=sys.stderr)
                sys.exit(1)
            from report_trace_attributes import run_from_file
            run_from_file(env, config, Path(rest[1]))
        else:
            from report_trace_attributes import run
            run(env, config)

    elif verb == "add-key":
        if len(rest) < 2:
            print("Usage: ./otm <env> add-key <url> <api_key>", file=sys.stderr)
            sys.exit(1)
        url, api_key = rest[0], rest[1]
        from auth import set_api_key
        set_api_key(url, api_key)
        print(f"API key stored for {url}")

    elif verb == "list-keys":
        from auth import load_api_keys
        keys = load_api_keys()
        if not keys:
            print("No API keys stored.")
        else:
            print("Stored cluster URLs:")
            for url in sorted(keys.keys()):
                print(f"  {url}")

    else:
        print(f"ERROR: Unknown verb '{verb}'", file=sys.stderr)
        usage()


if __name__ == "__main__":
    main()
