"""
Orchestrates the trace attribute scan across one or more ES clusters and
writes a JSON report to runs/<env>/<timestamp>/.

Each URL in the clusters file is queried directly as a standalone ES endpoint.
Derive URLs from GET _remote/info: take the proxy_address hostname, drop the
:9400 transport port, and prefix with https://.

Usage (via otm.py):
    ./otm <env> report
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from auth import get_api_key_or_raise
from kibana_client import KibanaClient
from scan_trace_attributes import ScanResult, coverage_report, process_field_caps

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


def scan_cluster(es_url: str, index_pattern: str) -> ScanResult:
    """Scan one ES cluster directly."""
    print(f"\nCluster: {es_url}", flush=True)
    try:
        api_key = get_api_key_or_raise(es_url)
    except RuntimeError as e:
        return ScanResult(
            es_url=es_url,
            cluster_name="unknown",
            cluster_version="unknown",
            index_pattern=index_pattern,
            total_span_docs=0,
            errors=[str(e)],
        )

    client = KibanaClient(es_url, api_key)

    cluster_name = "unknown"
    try:
        info = client.get_local_cluster_info()
        cluster_name = info.get("cluster_name", "unknown")
        print(f"  cluster_name: {cluster_name}", flush=True)
    except Exception as exc:
        print(f"  WARNING: could not fetch cluster info: {exc}", flush=True)

    result = ScanResult(
        es_url=es_url,
        cluster_name=cluster_name,
        cluster_version="unknown",
        index_pattern=index_pattern,
        total_span_docs=0,
    )

    try:
        result.total_span_docs = client.count(index_pattern)
        print(f"  Span documents: {result.total_span_docs:,}", flush=True)
    except Exception as exc:
        result.errors.append(f"_count failed: {exc}")
        print(f"  WARNING: _count failed: {exc}", flush=True)

    if result.total_span_docs == 0:
        print("  No trace data found — skipping field scan.", flush=True)
        result.errors.append(f"No documents in {index_pattern}")
        return result

    try:
        field_caps = client.field_caps(index_pattern)
        print(f"  Populated fields: {len(field_caps)}", flush=True)
    except Exception as exc:
        result.errors.append(f"_field_caps failed: {exc}")
        print(f"  WARNING: _field_caps failed: {exc}", flush=True)
        return result

    result.attributes = process_field_caps(field_caps)
    print(f"  Span attributes identified: {len(result.attributes)}", flush=True)
    return result


def build_report(env: str, config: dict, scan_results: list[ScanResult]) -> dict:
    expected_attributes: dict[str, str] = config.get("expected_attributes", {})
    clusters_data = []
    for sr in scan_results:
        cov = coverage_report(sr.attributes, expected_attributes)
        clusters_data.append({
            "es_url": sr.es_url,
            "cluster_name": sr.cluster_name,
            "index_pattern": sr.index_pattern,
            "total_span_docs": sr.total_span_docs,
            "attributes": [
                {
                    "apm_field": a.apm_field,
                    "otel_name": a.otel_name,
                    "field_type": a.field_type,
                    "is_label": a.is_label,
                    "is_known": a.is_known,
                }
                for a in sr.attributes
            ],
            "coverage": cov,
            "errors": sr.errors,
        })
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "environment": env,
        "configuration": {
            "index_pattern": config.get("index_pattern", "traces-apm*"),
            "expected_attributes": expected_attributes,
        },
        "clusters": clusters_data,
    }


def run_from_file(env: str, config: dict, path: Path) -> None:
    """Process a pre-exported _field_caps JSON response and write a report."""
    if not path.exists():
        print(f"ERROR: File not found: {path}", file=sys.stderr)
        sys.exit(1)

    with open(path) as f:
        raw = json.load(f)

    # Accept either the full _field_caps response (with "fields" key) or a bare fields dict
    field_caps = raw.get("fields", raw)

    attributes = process_field_caps(field_caps)
    print(f"Populated fields: {len(field_caps)}")
    print(f"Span attributes identified: {len(attributes)}")

    # Build a synthetic ScanResult so we can reuse build_report / _print_summary
    result = ScanResult(
        es_url=str(path),
        cluster_name=f"from-file:{path.name}",
        cluster_version="unknown",
        index_pattern=config.get("index_pattern", "traces-apm*"),
        total_span_docs=raw.get("total_span_docs", -1),
        attributes=attributes,
    )

    report = build_report(env, config, [result])

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = RUNS_DIR / env / timestamp
    out_dir.mkdir(parents=True, exist_ok=True)
    report_path = out_dir / "trace_attributes_report.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)

    latest = RUNS_DIR / env / "latest"
    if latest.is_symlink():
        latest.unlink()
    latest.symlink_to(timestamp)

    print(f"\nReport written to: {report_path}")
    _print_summary(report)


def run(env: str, config: dict) -> None:
    env_cfg = config.get("environments", {}).get(env)
    if env_cfg is None:
        print(f"ERROR: Unknown environment '{env}'. Check config.yaml.", file=sys.stderr)
        sys.exit(1)

    clusters_file = SCRIPT_DIR / env_cfg["clusters"]
    cluster_urls = load_cluster_urls(clusters_file)
    if not cluster_urls:
        print(
            f"ERROR: No cluster URLs in {clusters_file}.\n"
            "Derive from GET _remote/info: take proxy_address hostname, drop :9400, prefix https://",
            file=sys.stderr,
        )
        sys.exit(1)

    index_pattern = config.get("index_pattern", "traces-apm*")
    print(f"OTel trace attribute scanner — environment '{env}'")
    print(f"Index pattern: {index_pattern}")
    print(f"Clusters: {len(cluster_urls)}")

    all_results = [scan_cluster(url, index_pattern) for url in cluster_urls]
    report = build_report(env, config, all_results)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = RUNS_DIR / env / timestamp
    out_dir.mkdir(parents=True, exist_ok=True)
    report_path = out_dir / "trace_attributes_report.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)

    latest = RUNS_DIR / env / "latest"
    if latest.is_symlink():
        latest.unlink()
    latest.symlink_to(timestamp)

    print(f"\nReport written to: {report_path}")
    _print_summary(report)


def _print_summary(report: dict) -> None:
    print()
    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    for cluster in report["clusters"]:
        print(f"\nCluster: {cluster['cluster_name']}  ({cluster['es_url']})")
        doc_count = cluster['total_span_docs']
        doc_str = "unknown" if doc_count < 0 else f"{doc_count:,}"
        print(f"  Span documents:   {doc_str}")
        print(f"  Attributes found: {len(cluster['attributes'])}")
        cov = cluster["coverage"]
        print(f"  Coverage — matched: {len(cov['matched'])}  "
              f"unexpected: {len(cov['unexpected'])}  "
              f"missing: {len(cov['missing'])}")
        if cov["missing"]:
            print("  MISSING:")
            for name in cov["missing"]:
                print(f"    - {name}")
        if cluster["errors"]:
            print("  Errors:")
            for e in cluster["errors"]:
                print(f"    - {e}")

    seen: set[str] = set()
    all_attrs = []
    for cluster in report["clusters"]:
        for attr in cluster["attributes"]:
            if attr["otel_name"] not in seen:
                seen.add(attr["otel_name"])
                all_attrs.append(attr)
    all_attrs.sort(key=lambda a: a["otel_name"])

    if all_attrs:
        print(f"\nAll distinct attributes ({len(all_attrs)}):")
        print("-" * 70)
        for attr in all_attrs:
            flags = (" [label]" if attr["is_label"] else "") + \
                    ("" if attr["is_known"] else " [UNKNOWN MAPPING]")
            print(f"  {attr['otel_name']:<50}  {attr['apm_field']}{flags}")
    print()
