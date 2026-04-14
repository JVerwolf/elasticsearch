#!/usr/bin/env python3
"""
otm — OTel Trace Migration scanner
===================================
Scans Elasticsearch observability clusters for APM span attributes,
producing a report that can be used to verify that the APM agent →
OTel SDK migration does not drop any attributes currently in use.

Usage:
    ./otm <environment> <verb> [args...]

Verbs:
    report              Scan clusters and write a JSON attribute report
    add-key <url> <key> Store an API key for the given ES cluster URL
    list-keys           List all stored cluster URLs (not the keys themselves)

Environments are defined in config.yaml (e.g. 'qa', 'prod').

Examples:
    ./otm qa add-key https://my-cluster.es.us-east-1.aws.qa.cld.elstc.co:9243 VGhpcyBpcyBhIGZha2U=
    ./otm qa report
    ./otm qa report   # runs report again; symlink 'runs/qa/latest' updated
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
        from report_trace_attributes import run
        run(env, config)

    elif verb == "add-key":
        if len(rest) < 2:
            print("Usage: ./otm <env> add-key <es_url> <api_key>", file=sys.stderr)
            sys.exit(1)
        es_url, api_key = rest[0], rest[1]
        from auth import set_api_key
        set_api_key(es_url, api_key)
        print(f"API key stored for {es_url}")

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
