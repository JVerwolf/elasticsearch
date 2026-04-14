"""
Orchestrates the trace attribute scan for one or more clusters and writes a
JSON report to runs/<env>/<timestamp>/.

Usage (via otm.py):
    ./otm <env> report
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

from auth import auth_headers
from es_client import ElasticsearchClient
from scan_trace_attributes import (
    AttributeInfo,
    ScanResult,
    coverage_report,
    process_field_caps,
)

SCRIPT_DIR = Path(__file__).parent
RUNS_DIR = SCRIPT_DIR / "runs"


def load_cluster_urls(path: Path) -> list[str]:
    urls = []
    if not path.exists():
        return urls
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                urls.append(line)
    return urls


def scan_cluster(es_url: str, index_pattern: str, expected_attributes: dict[str, str]) -> ScanResult:
    """Scan a single Elasticsearch cluster for trace span attributes."""
    print(f"  Connecting to {es_url} …", flush=True)
    try:
        headers = auth_headers(es_url)
    except RuntimeError as e:
        return ScanResult(
            es_url=es_url,
            cluster_name="unknown",
            cluster_version="unknown",
            index_pattern=index_pattern,
            total_span_docs=0,
            errors=[str(e)],
        )

    client = ElasticsearchClient(es_url, headers)

    # Cluster metadata
    try:
        info = client.get_cluster_info()
        cluster_name = info.get("cluster_name", "unknown")
        cluster_version = info.get("version", {}).get("number", "unknown")
        print(f"    Cluster: {cluster_name} (ES {cluster_version})", flush=True)
    except Exception as exc:
        return ScanResult(
            es_url=es_url,
            cluster_name="unknown",
            cluster_version="unknown",
            index_pattern=index_pattern,
            total_span_docs=0,
            errors=[f"Failed to connect: {exc}"],
        )

    result = ScanResult(
        es_url=es_url,
        cluster_name=cluster_name,
        cluster_version=cluster_version,
        index_pattern=index_pattern,
        total_span_docs=0,
    )

    # Total document count
    try:
        result.total_span_docs = client.count(index_pattern)
        print(f"    Total span documents: {result.total_span_docs:,}", flush=True)
    except Exception as exc:
        result.errors.append(f"Count failed: {exc}")

    if result.total_span_docs == 0:
        print("    No trace data found. Skipping field scan.", flush=True)
        result.errors.append(f"No documents found in {index_pattern}")
        return result

    # Field caps — discover all populated fields
    print("    Fetching field capabilities …", flush=True)
    try:
        field_caps = client.field_caps(index_pattern)
        print(f"    Found {len(field_caps)} populated fields", flush=True)
    except Exception as exc:
        result.errors.append(f"_field_caps failed: {exc}")
        return result

    # Classify fields as OTel span attributes
    result.attributes = process_field_caps(field_caps)
    print(f"    Identified {len(result.attributes)} span attribute fields", flush=True)

    return result


def build_report(
    env: str,
    config: dict,
    scan_results: list[ScanResult],
) -> dict:
    expected_attributes: dict[str, str] = config.get("expected_attributes", {})
    clusters_data = []
    for sr in scan_results:
        attrs_dicts = [
            {
                "apm_field": a.apm_field,
                "otel_name": a.otel_name,
                "field_type": a.field_type,
                "is_label": a.is_label,
                "is_known": a.is_known,
            }
            for a in sr.attributes
        ]
        cov = coverage_report(sr.attributes, expected_attributes)
        clusters_data.append(
            {
                "es_url": sr.es_url,
                "cluster_name": sr.cluster_name,
                "cluster_version": sr.cluster_version,
                "index_pattern": sr.index_pattern,
                "total_span_docs": sr.total_span_docs,
                "attributes": attrs_dicts,
                "coverage": cov,
                "errors": sr.errors,
            }
        )

    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "environment": env,
        "configuration": {
            "index_pattern": config.get("index_pattern", "traces-apm*"),
            "expected_attributes": expected_attributes,
        },
        "clusters": clusters_data,
    }


def run(env: str, config: dict) -> None:
    env_cfg = config.get("environments", {}).get(env)
    if env_cfg is None:
        print(f"ERROR: Unknown environment '{env}'. Check config.yaml.", file=sys.stderr)
        sys.exit(1)

    clusters_file = SCRIPT_DIR / env_cfg["clusters"]
    cluster_urls = load_cluster_urls(clusters_file)
    if not cluster_urls:
        print(
            f"ERROR: No cluster URLs found in {clusters_file}.\n"
            "Add one ES URL per line (see clusters.txt.example).",
            file=sys.stderr,
        )
        sys.exit(1)

    index_pattern = config.get("index_pattern", "traces-apm*")
    expected_attributes: dict[str, str] = config.get("expected_attributes", {})

    print(f"Scanning {len(cluster_urls)} cluster(s) in environment '{env}'")
    print(f"Index pattern: {index_pattern}")
    print()

    scan_results = []
    for url in cluster_urls:
        print(f"Cluster: {url}")
        sr = scan_cluster(url, index_pattern, expected_attributes)
        scan_results.append(sr)
        if sr.errors:
            for e in sr.errors:
                print(f"  WARNING: {e}", file=sys.stderr)
        print()

    report = build_report(env, config, scan_results)

    # Write output
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = RUNS_DIR / env / timestamp
    out_dir.mkdir(parents=True, exist_ok=True)

    report_path = out_dir / "trace_attributes_report.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)

    # Update 'latest' symlink
    latest_link = RUNS_DIR / env / "latest"
    if latest_link.is_symlink():
        latest_link.unlink()
    latest_link.symlink_to(timestamp)

    print(f"Report written to: {report_path}")
    print()

    # Print a summary to stdout
    _print_summary(report)


def _print_summary(report: dict) -> None:
    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    for cluster in report["clusters"]:
        print(f"\nCluster: {cluster['cluster_name']} ({cluster['es_url']})")
        print(f"  ES version:       {cluster['cluster_version']}")
        print(f"  Span documents:   {cluster['total_span_docs']:,}")
        print(f"  Attributes found: {len(cluster['attributes'])}")
        cov = cluster["coverage"]
        print(f"  Coverage:")
        print(f"    Matched expected:   {len(cov['matched'])}")
        print(f"    Unexpected (extra): {len(cov['unexpected'])}")
        print(f"    Missing:            {len(cov['missing'])}")

        if cov["missing"]:
            print("\n  MISSING attributes (in expected set but not found in data):")
            for name in cov["missing"]:
                print(f"    - {name}")

        if cov["unexpected"]:
            print("\n  UNEXPECTED attributes (found in data but not in expected set):")
            for name in cov["unexpected"]:
                print(f"    - {name}")

        if cluster["errors"]:
            print("\n  Errors:")
            for e in cluster["errors"]:
                print(f"    - {e}")

    print()
    print("All attributes found (OTel name → APM field):")
    print("-" * 70)
    all_attrs: list[dict] = []
    for cluster in report["clusters"]:
        for attr in cluster["attributes"]:
            if attr not in all_attrs:
                all_attrs.append(attr)
    all_attrs.sort(key=lambda a: a["otel_name"])
    for attr in all_attrs:
        label_flag = " [label]" if attr["is_label"] else ""
        known_flag = "" if attr["is_known"] else " [UNKNOWN MAPPING]"
        print(f"  {attr['otel_name']:<50}  {attr['apm_field']}{label_flag}{known_flag}")
    print()
