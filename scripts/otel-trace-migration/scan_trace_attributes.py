"""
Core logic for scanning trace attributes in APM data.

This module is pure logic — no I/O, no network calls.  All data comes in as
plain Python dicts so that unit tests can exercise it without mocking.

APM data model notes
--------------------
When the Elasticsearch APM Java agent intercepts OpenTelemetry spans, it maps
span attributes to APM's own data model before storing them in traces-apm*:

  OTel attribute              APM field
  --------------------------  --------------------------------
  http.method                 http.request.method  (or labels.http_method in older versions)
  http.url                    url.full
  http.status_code            http.response.status_code
  es.node.name                labels.es_node_name
  es.cluster.name             labels.es_cluster_name
  es.x-opaque-id              labels.es_x_opaque_id
  es.task.id                  labels.es_task_id
  es.task.parent.id           labels.es_task_parent_id
  http.flavour                labels.http_flavour   (not a standard APM field)
  http.request.headers.*      labels.http_request_headers_*
  http.response.headers.*     labels.http_response_headers_*

The LABELS_TO_OTEL dict below encodes known mappings from APM label keys back
to the original OTel attribute names.  Unrecognised labels are reported as-is.

Standard APM fields (non-label) that correspond to OTel attributes are listed
in STANDARD_FIELDS_TO_OTEL.
"""

from __future__ import annotations

import fnmatch
import re
from dataclasses import dataclass, field
from typing import Any

# ---------------------------------------------------------------------------
# Known mappings: APM label key → original OTel attribute name
# (label keys use underscores where OTel uses dots or hyphens)
# ---------------------------------------------------------------------------
LABELS_TO_OTEL: dict[str, str] = {
    "es_node_name": "es.node.name",
    "es_cluster_name": "es.cluster.name",
    "es_x_opaque_id": "es.x-opaque-id",
    "es_task_id": "es.task.id",
    "es_task_parent_id": "es.task.parent.id",
    "http_flavour": "http.flavour",
    # http.request.headers.* → labels.http_request_headers_*
    # http.response.headers.* → labels.http_response_headers_*
    # (handled dynamically by prefix matching)
}

LABEL_PREFIX_MAPPINGS: list[tuple[str, str]] = [
    # (label_prefix, otel_prefix)
    ("http_request_headers_", "http.request.headers."),
    ("http_response_headers_", "http.response.headers."),
    ("otel_attributes_", "otel.attributes."),   # APM agent adds this prefix to unknown OTel attrs
]

# Standard (non-label) APM fields that correspond to OTel attributes
STANDARD_FIELDS_TO_OTEL: dict[str, str] = {
    "http.request.method": "http.method",
    "http.response.status_code": "http.status_code",
    "url.full": "http.url",
    # The APM agent also sets these from OTel resource attributes:
    "service.node.name": "es.node.name",     # alternative mapping in some versions
}

# Fields that are part of the APM data model but are NOT OTel span attributes
# (infrastructure fields, agent metadata, etc.)
APM_INFRASTRUCTURE_PREFIXES = (
    "agent.",
    "data_stream.",
    "ecs.",
    "event.",
    "observer.",
    "processor.",
    "service.",
    "timestamp",
    "@timestamp",
    "_",
)


@dataclass
class AttributeInfo:
    """A single span attribute found in the trace data."""
    apm_field: str              # Field name as stored in APM (e.g. "labels.es_node_name")
    otel_name: str              # Reconstructed OTel attribute name (e.g. "es.node.name")
    field_type: str             # ES field type (keyword, long, etc.)
    is_label: bool              # True if stored in labels.*
    is_known: bool              # True if recognised in LABELS_TO_OTEL / STANDARD_FIELDS_TO_OTEL
    doc_count: int = 0          # Number of docs with this field (0 if unknown)


@dataclass
class ScanResult:
    """Results from scanning one cluster."""
    es_url: str
    cluster_name: str
    cluster_version: str
    index_pattern: str
    total_span_docs: int
    attributes: list[AttributeInfo] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def label_key_to_otel(label_key: str) -> str:
    """
    Convert an APM label key back to its OTel attribute name.

    APM replaces dots and hyphens with underscores in label keys.  This is a
    lossy transformation, so we use the known-mappings dict first and fall back
    to a best-effort dot substitution.
    """
    if label_key in LABELS_TO_OTEL:
        return LABELS_TO_OTEL[label_key]
    for prefix, otel_prefix in LABEL_PREFIX_MAPPINGS:
        if label_key.startswith(prefix):
            suffix = label_key[len(prefix):]
            # Re-introduce dots in the suffix using heuristic: single underscores
            # between word characters are likely dots in the original name.
            suffix_dotted = re.sub(r"(?<=[a-zA-Z0-9])_(?=[a-zA-Z0-9])", ".", suffix)
            return otel_prefix + suffix_dotted
    # Best-effort: replace underscores with dots
    return label_key.replace("_", ".")


def is_apm_infrastructure_field(field_name: str) -> bool:
    """Return True for APM infrastructure fields that are not OTel span attributes."""
    return any(field_name.startswith(p) for p in APM_INFRASTRUCTURE_PREFIXES)


def classify_field(field_name: str, field_type: str) -> AttributeInfo | None:
    """
    Classify an APM field as an OTel span attribute, or return None if it's
    an APM infrastructure field we should skip.

    Args:
        field_name: Full APM field name (e.g. "labels.es_node_name")
        field_type: ES field type (e.g. "keyword")

    Returns:
        AttributeInfo if this is a span attribute, None otherwise.
    """
    if is_apm_infrastructure_field(field_name):
        return None

    # labels.* — custom span attributes stored by the APM agent
    if field_name.startswith("labels."):
        label_key = field_name[len("labels."):]
        otel_name = label_key_to_otel(label_key)
        is_known = label_key in LABELS_TO_OTEL or any(
            label_key.startswith(p) for p, _ in LABEL_PREFIX_MAPPINGS
        )
        return AttributeInfo(
            apm_field=field_name,
            otel_name=otel_name,
            field_type=field_type,
            is_label=True,
            is_known=is_known,
        )

    # Standard APM fields that map to OTel attributes
    if field_name in STANDARD_FIELDS_TO_OTEL:
        return AttributeInfo(
            apm_field=field_name,
            otel_name=STANDARD_FIELDS_TO_OTEL[field_name],
            field_type=field_type,
            is_label=False,
            is_known=True,
        )

    # http.*, span.*, transaction.*, url.* — could be OTel or APM-specific
    # Include them but mark as "not known" so the operator can review.
    for prefix in ("http.", "span.", "transaction.", "url.", "trace.", "error."):
        if field_name.startswith(prefix):
            return AttributeInfo(
                apm_field=field_name,
                otel_name=field_name,   # assume 1:1 for now
                field_type=field_type,
                is_label=False,
                is_known=field_name in STANDARD_FIELDS_TO_OTEL,
            )

    return None


def process_field_caps(field_caps: dict[str, Any]) -> list[AttributeInfo]:
    """
    Turn the _field_caps response into a list of AttributeInfo objects,
    filtering out APM infrastructure fields.

    field_caps: the "fields" dict from the _field_caps API response.
    """
    results: list[AttributeInfo] = []
    for field_name, type_info in field_caps.items():
        # type_info maps ES type → capability metadata
        # Use the first (usually only) type present
        field_type = next(iter(type_info.keys()), "unknown")
        attr = classify_field(field_name, field_type)
        if attr is not None:
            results.append(attr)

    results.sort(key=lambda a: (not a.is_label, a.otel_name))
    return results


def coverage_report(
    found_attributes: list[AttributeInfo],
    expected_attributes: dict[str, str],
) -> dict:
    """
    Compare found attributes against the expected set from config.yaml.

    Returns a dict with:
      - matched: OTel names found in data AND in expected set
      - unexpected: OTel names found in data but NOT in expected set
      - missing: OTel names in expected set but NOT found in data
    """
    found_otel_names = {a.otel_name for a in found_attributes}

    matched = []
    unexpected = []
    missing = []

    for attr in found_attributes:
        # Check against expected set (with glob matching for wildcard entries)
        is_expected = any(
            fnmatch.fnmatch(attr.otel_name, pattern)
            for pattern in expected_attributes
        )
        if is_expected:
            matched.append(attr.otel_name)
        else:
            unexpected.append(attr.otel_name)

    for expected_key in expected_attributes:
        if not any(fnmatch.fnmatch(name, expected_key) for name in found_otel_names):
            # Only flag as missing if the expected key is not a wildcard pattern
            # that might simply have zero matching instances in this dataset
            if "*" not in expected_key:
                missing.append(expected_key)

    return {
        "matched": sorted(matched),
        "unexpected": sorted(unexpected),
        "missing": sorted(missing),
    }
