"""
End-to-end tests for the OTel trace migration scanner.

These tests run the full scanner pipeline against synthetic _field_caps fixtures,
verifying that the classification, mapping, and coverage logic works correctly on
realistic input without needing a live Elasticsearch cluster.

Background
----------
Pre-migration (APM Java agent path):
  The APM Java agent intercepts ES's OTel API calls and exports spans in APM NDJSON
  format. In that format, OTel attributes live in a nested 'otel.attributes' object.
  APM Server stores these in ES as labels with an 'otel_attributes_' prefix:
      otel.attributes.es.node.name  →  labels.otel_attributes_es_node_name

Post-migration (OTel SDK path, elastic/elasticsearch#146096):
  The OtelSdkExportTracerSupplier sends OTLP directly to APM Server. APM Server maps
  custom OTel attributes to clean labels without the prefix:
      es.node.name  →  labels.es_node_name

The scanner (scan_trace_attributes.py) detects this change: before migration the
expected attributes (es.node.name, etc.) are MISSING from coverage; after migration
they are MATCHED.

Run with:
    python -m pytest test_e2e.py -v
    # or directly:
    python test_e2e.py
"""

from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import yaml

SCRIPT_DIR = Path(__file__).parent
TEST_DATA_DIR = SCRIPT_DIR / "test_data"


def load_field_caps(filename: str) -> dict:
    path = TEST_DATA_DIR / filename
    with open(path) as f:
        raw = json.load(f)
    return raw.get("fields", raw)


def load_config() -> dict:
    with open(SCRIPT_DIR / "config.yaml") as f:
        return yaml.safe_load(f)


# Import scanner modules directly (same venv)
from scan_trace_attributes import coverage_report, process_field_caps
from report_trace_attributes import run_from_file


class TestPreMigrationFixture(unittest.TestCase):
    """
    Pre-migration: APM Java agent stores OTel attributes with otel_attributes_ prefix.

    Custom attributes like es.node.name land in labels.otel_attributes_es_node_name.
    The scanner maps these to otel.attributes.es.node.name — a DIFFERENT namespace
    than the expected es.node.name, so coverage shows them as MISSING.
    """

    @classmethod
    def setUpClass(cls):
        field_caps = load_field_caps("field_caps_pre_migration.json")
        cls.attributes = process_field_caps(field_caps)
        cls.by_otel = {a.otel_name: a for a in cls.attributes}
        cls.config = load_config()

    # --- Custom ES attributes are stored with otel_attributes_ prefix ---

    def test_es_node_name_stored_with_otel_prefix(self):
        # Pre-migration: the APM agent wraps unknown OTel attrs in otel.attributes.*
        self.assertIn("otel.attributes.es.node.name", self.by_otel)
        self.assertNotIn("es.node.name", self.by_otel)

    def test_es_cluster_name_stored_with_otel_prefix(self):
        self.assertIn("otel.attributes.es.cluster.name", self.by_otel)
        self.assertNotIn("es.cluster.name", self.by_otel)

    def test_http_flavour_stored_with_otel_prefix(self):
        self.assertIn("otel.attributes.http.flavour", self.by_otel)
        self.assertNotIn("http.flavour", self.by_otel)

    def test_http_request_header_stored_with_otel_prefix(self):
        # otel_attributes_ prefix + content_type suffix → otel.attributes.http.request.headers.content.type
        self.assertIn("otel.attributes.http.request.headers.content.type", self.by_otel)

    # --- Standard HTTP attributes are native APM fields (same in both paths) ---

    def test_http_method_is_native_apm_field(self):
        self.assertIn("http.method", self.by_otel)
        self.assertEqual(self.by_otel["http.method"].apm_field, "http.request.method")
        self.assertFalse(self.by_otel["http.method"].is_label)

    def test_http_status_code_is_native_apm_field(self):
        self.assertIn("http.status_code", self.by_otel)
        self.assertFalse(self.by_otel["http.status_code"].is_label)

    def test_http_url_is_native_apm_field(self):
        self.assertIn("http.url", self.by_otel)
        self.assertFalse(self.by_otel["http.url"].is_label)

    # --- Coverage shows expected ES attributes as MISSING pre-migration ---

    def test_coverage_es_attributes_missing(self):
        expected = self.config.get("expected_attributes", {})
        cov = coverage_report(self.attributes, expected)
        # es.node.name, es.cluster.name etc. are stored as otel.attributes.* so they're MISSING
        self.assertIn("es.node.name", cov["missing"])
        self.assertIn("es.cluster.name", cov["missing"])
        self.assertIn("es.x-opaque-id", cov["missing"])
        self.assertIn("es.task.id", cov["missing"])
        self.assertIn("es.task.parent.id", cov["missing"])
        self.assertIn("http.flavour", cov["missing"])

    def test_coverage_http_attributes_matched(self):
        expected = self.config.get("expected_attributes", {})
        cov = coverage_report(self.attributes, expected)
        # Standard HTTP attrs land in native APM fields and ARE present
        self.assertIn("http.method", cov["matched"])
        self.assertIn("http.status_code", cov["matched"])
        self.assertIn("http.url", cov["matched"])

    # --- Infrastructure fields are excluded ---

    def test_infrastructure_fields_excluded(self):
        otel_names = {a.otel_name for a in self.attributes}
        for name in otel_names:
            self.assertFalse(name.startswith("data_stream."))
            self.assertFalse(name.startswith("agent."))
            self.assertFalse(name.startswith("service."))


class TestPostMigrationFixture(unittest.TestCase):
    """
    Post-migration (elastic/elasticsearch#146096): OTel SDK sends OTLP to APM Server.

    APM Server maps custom OTel attributes directly to labels.* without the prefix:
        es.node.name  →  labels.es_node_name  →  scanner: es.node.name  →  MATCHED
    Standard HTTP attributes still land in native APM fields.
    """

    @classmethod
    def setUpClass(cls):
        field_caps = load_field_caps("field_caps_post_migration.json")
        cls.attributes = process_field_caps(field_caps)
        cls.by_otel = {a.otel_name: a for a in cls.attributes}
        cls.config = load_config()

    # --- Custom ES attributes now land in clean labels.* ---

    def test_es_node_name_clean(self):
        self.assertIn("es.node.name", self.by_otel)
        self.assertNotIn("otel.attributes.es.node.name", self.by_otel)
        self.assertTrue(self.by_otel["es.node.name"].is_label)
        self.assertTrue(self.by_otel["es.node.name"].is_known)

    def test_es_cluster_name_clean(self):
        self.assertIn("es.cluster.name", self.by_otel)
        self.assertNotIn("otel.attributes.es.cluster.name", self.by_otel)

    def test_http_flavour_clean(self):
        self.assertIn("http.flavour", self.by_otel)
        self.assertNotIn("otel.attributes.http.flavour", self.by_otel)

    def test_all_es_attributes_present(self):
        for name in ["es.node.name", "es.cluster.name", "es.x-opaque-id", "es.task.id", "es.task.parent.id"]:
            self.assertIn(name, self.by_otel, f"{name} should be present post-migration")

    # --- Standard HTTP attributes unchanged ---

    def test_http_method_still_native(self):
        self.assertIn("http.method", self.by_otel)
        self.assertEqual(self.by_otel["http.method"].apm_field, "http.request.method")

    # --- Coverage shows ALL expected attributes matched post-migration ---

    def test_coverage_all_expected_matched(self):
        expected = self.config.get("expected_attributes", {})
        cov = coverage_report(self.attributes, expected)
        self.assertEqual(
            cov["missing"],
            [],
            f"Post-migration should have no missing attributes, but missing: {cov['missing']}",
        )

    def test_coverage_no_unexpected_known_attributes(self):
        expected = self.config.get("expected_attributes", {})
        cov = coverage_report(self.attributes, expected)
        # otel.attributes.* names should NOT appear in post-migration
        for name in cov["unexpected"]:
            self.assertFalse(
                name.startswith("otel.attributes."),
                f"Post-migration should not have otel.attributes.* attrs, found: {name}",
            )


class TestMigrationComparison(unittest.TestCase):
    """
    Compares the scanner output before and after the OTel SDK migration (PR #146096).

    This is the core migration verification: the same ES API request generates spans
    with the same semantic attributes, but the storage format changes and the scanner
    correctly detects whether the expected attributes are present.
    """

    @classmethod
    def setUpClass(cls):
        pre_caps = load_field_caps("field_caps_pre_migration.json")
        post_caps = load_field_caps("field_caps_post_migration.json")
        cls.pre_attrs = process_field_caps(pre_caps)
        cls.post_attrs = process_field_caps(post_caps)
        cls.pre_otel = {a.otel_name for a in cls.pre_attrs}
        cls.post_otel = {a.otel_name for a in cls.post_attrs}
        cls.config = load_config()
        cls.expected = cls.config.get("expected_attributes", {})

    def test_standard_http_attributes_present_in_both(self):
        """Standard HTTP OTel attributes land in native APM fields in both paths."""
        for name in ["http.method", "http.status_code", "http.url"]:
            self.assertIn(name, self.pre_otel, f"{name} missing pre-migration")
            self.assertIn(name, self.post_otel, f"{name} missing post-migration")

    def test_es_attributes_namespace_changes(self):
        """
        ES-specific OTel attributes change namespace between pre and post migration.
        Pre:  otel.attributes.es.node.name  (APM agent wraps with otel.attributes. prefix)
        Post: es.node.name                  (OTel SDK → APM Server stores directly)
        """
        self.assertIn("otel.attributes.es.node.name", self.pre_otel)
        self.assertNotIn("es.node.name", self.pre_otel)

        self.assertIn("es.node.name", self.post_otel)
        self.assertNotIn("otel.attributes.es.node.name", self.post_otel)

    def test_coverage_missing_before_migration(self):
        """Pre-migration: ES attributes stored in wrong namespace → reported as MISSING."""
        cov = coverage_report(self.pre_attrs, self.expected)
        self.assertGreater(len(cov["missing"]), 0, "Pre-migration should have missing attributes")
        self.assertIn("es.node.name", cov["missing"])
        self.assertIn("es.cluster.name", cov["missing"])

    def test_coverage_complete_after_migration(self):
        """Post-migration: all expected attributes present → no MISSING."""
        cov = coverage_report(self.post_attrs, self.expected)
        self.assertEqual(cov["missing"], [], f"Post-migration missing: {cov['missing']}")

    def test_all_expected_attributes_covered_post_migration(self):
        """Every attribute in config.yaml expected_attributes is matched post-migration."""
        cov = coverage_report(self.post_attrs, self.expected)
        for name in self.expected:
            if "*" not in name:  # skip glob patterns
                self.assertIn(name, cov["matched"], f"{name} not matched post-migration")


class TestRunFromFile(unittest.TestCase):
    """run_from_file() produces a complete report for both fixtures."""

    def _run(self, filename: str) -> str:
        config = load_config()
        buf = io.StringIO()
        with redirect_stdout(buf):
            run_from_file("qa", config, TEST_DATA_DIR / filename)
        return buf.getvalue()

    def test_pre_migration_report_runs(self):
        output = self._run("field_caps_pre_migration.json")
        self.assertIn("Span attributes identified:", output)
        self.assertIn("SUMMARY", output)
        self.assertIn("MISSING", output)  # pre-migration has missing attributes

    def test_post_migration_report_runs(self):
        output = self._run("field_caps_post_migration.json")
        self.assertIn("Span attributes identified:", output)
        self.assertIn("SUMMARY", output)
        # Post-migration should not report any missing attributes
        self.assertNotIn("MISSING", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
