"""
Orchestrates the trace attribute scan across all clusters reachable from a
Kibana overview instance and writes a JSON report to runs/<env>/<timestamp>/.

Cluster discovery:
  1. The local ES cluster that Kibana is connected to (always scanned).
  2. Any CCS remote clusters returned by GET /_remote/info that report
     connected=true.

Usage (via otm.py):
    ./otm <env> report
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

from auth import get_api_key_or_raise
from kibana_client import KibanaClient
from scan_trace_attributes import (
    ScanResult,
    coverage_report,
    process_field_caps,
)

SCRIPT_DIR = Path(__file__).parent
RUNS_DIR = SCRIPT_DIR / "runs"


def load_kibana_urls(path: Path) -> list[str]:
    urls = []
    if not path.exists():
        return urls
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                urls.append(line)
    return urls


def scan_cluster(
    client: KibanaClient,
    index_pattern: str,
    cluster: str | None,
    expected_attributes: dict[str, str],
) -> ScanResult:
    """
    Scan one cluster (local or CCS remote) for trace span attributes.

    cluster=None  → local ES cluster (no CCS prefix)
    cluster=str   → remote cluster name (prefixed as "name:index_pattern")
    """
    label = cluster if cluster else "(local)"
    print(f"    Scanning cluster: {label}", flush=True)

    # Cluster identity — only queryable for the local cluster
    cluster_name = cluster or "local"
    cluster_version = "unknown"
    if cluster is None:
        try:
            info = client.get_local_cluster_info()
            cluster_name = info.get("cluster_name", "local")
            cluster_version = info.get("version", {}).get("number", "unknown")
            print(f"      Name: {cluster_name}  (ES {cluster_version})", flush=True)
        except Exception as exc:
            print(f"      WARNING: could not fetch cluster info: {exc}", flush=True)

    result = ScanResult(
        es_url=f"{client.kibana_url} → {cluster or 'local'}",
        cluster_name=cluster_name,
        cluster_version=cluster_version,
        index_pattern=index_pattern,
        total_span_docs=0,
    )

    # Document count
    try:
        result.total_span_docs = client.count(index_pattern, cluster)
        print(f"      Span documents: {result.total_span_docs:,}", flush=True)
    except Exception as exc:
        result.errors.append(f"_count failed: {exc}")
        print(f"      WARNING: _count failed: {exc}", flush=True)

    if result.total_span_docs == 0:
        print("      No trace data found — skipping field scan.", flush=True)
        result.errors.append(f"No documents in {index_pattern}")
        return result

    # Field caps
    try:
        field_caps = client.field_caps(index_pattern, cluster)
        print(f"      Populated fields: {len(field_caps)}", flush=True)
    except Exception as exc:
        result.errors.append(f"_field_caps failed: {exc}")
        print(f"      WARNING: _field_caps failed: {exc}", flush=True)
        return result

    result.attributes = process_field_caps(field_caps)
    print(f"      Span attributes identified: {len(result.attributes)}", flush=True)
    return result


def scan_kibana(kibana_url: str, config: dict) -> list[ScanResult]:
    """Connect to one Kibana instance, discover clusters, scan each one."""
    index_pattern = config.get("index_pattern", "traces-apm*")
    expected_attributes: dict[str, str] = config.get("expected_attributes", {})

    print(f"\nKibana: {kibana_url}", flush=True)
    try:
        api_key = get_api_key_or_raise(kibana_url)
    except RuntimeError as e:
        sr = ScanResult(
            es_url=kibana_url,
            cluster_name="unknown",
            cluster_version="unknown",
            index_pattern=index_pattern,
            total_span_docs=0,
            errors=[str(e)],
        )
        return [sr]

    client = KibanaClient(kibana_url, api_key)

    # Discover clusters
    print("  Discovering clusters via /_remote/info …", flush=True)
    try:
        clusters = client.discover_cluster_names()
    except Exception as exc:
        print(f"  WARNING: cluster discovery failed: {exc}", flush=True)
        clusters = [None]

    remote_names = [c for c in clusters if c is not None]
    print(
        f"  Found: local + {len(remote_names)} remote cluster(s)"
        + (f": {', '.join(remote_names)}" if remote_names else ""),
        flush=True,
    )

    results = []
    for cluster in clusters:
        sr = scan_cluster(client, index_pattern, cluster, expected_attributes)
        results.append(sr)

    return results


def build_report(env: str, config: dict, scan_results: list[ScanResult]) -> dict:
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
                "kibana_or_es_url": sr.es_url,
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

    kibana_file = SCRIPT_DIR / env_cfg["clusters"]
    kibana_urls = load_kibana_urls(kibana_file)
    if not kibana_urls:
        print(
            f"ERROR: No Kibana URLs found in {kibana_file}.\n"
            "Add one Kibana URL per line (see clusters.txt.example).",
            file=sys.stderr,
        )
        sys.exit(1)

    index_pattern = config.get("index_pattern", "traces-apm*")
    print(f"OTel trace attribute scanner — environment '{env}'")
    print(f"Index pattern: {index_pattern}")

    all_results: list[ScanResult] = []
    for kibana_url in kibana_urls:
        results = scan_kibana(kibana_url, config)
        all_results.extend(results)

    report = build_report(env, config, all_results)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = RUNS_DIR / env / timestamp
    out_dir.mkdir(parents=True, exist_ok=True)

    report_path = out_dir / "trace_attributes_report.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)

    latest_link = RUNS_DIR / env / "latest"
    if latest_link.is_symlink():
        latest_link.unlink()
    latest_link.symlink_to(timestamp)

    print(f"\nReport written to: {report_path}")
    _print_summary(report)


def _print_summary(report: dict) -> None:
    print()
    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    for cluster in report["clusters"]:
        print(f"\nCluster: {cluster['cluster_name']}  ({cluster['kibana_or_es_url']})")
        print(f"  ES version:       {cluster['cluster_version']}")
        print(f"  Span documents:   {cluster['total_span_docs']:,}")
        print(f"  Attributes found: {len(cluster['attributes'])}")
        cov = cluster["coverage"]
        print(f"  Coverage:")
        print(f"    Matched expected:   {len(cov['matched'])}")
        print(f"    Unexpected (extra): {len(cov['unexpected'])}")
        print(f"    Missing:            {len(cov['missing'])}")

        if cov["missing"]:
            print("\n  MISSING (expected but not found in data):")
            for name in cov["missing"]:
                print(f"    - {name}")

        if cov["unexpected"]:
            print("\n  UNEXPECTED (in data, not in expected set):")
            for name in cov["unexpected"][:20]:   # cap at 20 for readability
                print(f"    - {name}")
            if len(cov["unexpected"]) > 20:
                print(f"    … and {len(cov['unexpected']) - 20} more (see JSON report)")

        if cluster["errors"]:
            print("\n  Errors:")
            for e in cluster["errors"]:
                print(f"    - {e}")

    # Combined attribute list across all clusters
    seen: set[str] = set()
    all_attrs = []
    for cluster in report["clusters"]:
        for attr in cluster["attributes"]:
            if attr["otel_name"] not in seen:
                seen.add(attr["otel_name"])
                all_attrs.append(attr)
    all_attrs.sort(key=lambda a: a["otel_name"])

    print()
    print(f"All distinct attributes found across all clusters ({len(all_attrs)}):")
    print("-" * 70)
    for attr in all_attrs:
        label_flag = " [label]" if attr["is_label"] else ""
        known_flag = "" if attr["is_known"] else " [UNKNOWN MAPPING]"
        print(f"  {attr['otel_name']:<50}  {attr['apm_field']}{label_flag}{known_flag}")
    print()
