"""
End-to-end tests for the OTel trace migration scanner.

These tests run the full scanner pipeline against synthetic _field_caps fixtures,
verifying that the classification, mapping, and coverage logic works correctly on
realistic input without needing a live Elasticsearch cluster.

Run with:
    python -m pytest test_e2e.py -v
    # or directly:
    python test_e2e.py
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
TEST_DATA_DIR = SCRIPT_DIR / "test_data"

# Import scanner modules directly (same venv)
from scan_trace_attributes import (
    AttributeInfo,
    coverage_report,
    process_field_caps,
)
from report_trace_attributes import build_report, run_from_file
from scan_trace_attributes import ScanResult


def load_field_caps(filename: str) -> dict:
    path = TEST_DATA_DIR / filename
    with open(path) as f:
        raw = json.load(f)
    return raw.get("fields", raw)


class TestPreMigrationFixture(unittest.TestCase):
    """Scanner produces correct output for the pre-migration (APM Java agent) fixture."""

    @classmethod
    def setUpClass(cls):
        field_caps = load_field_caps("field_caps_pre_migration.json")
        cls.attributes = process_field_caps(field_caps)
        cls.by_apm = {a.apm_field: a for a in cls.attributes}
        cls.by_otel = {a.otel_name: a for a in cls.attributes}

    # --- label-based OTel attributes ---

    def test_es_node_name_present(self):
        self.assertIn("es.node.name", self.by_otel)

    def test_es_cluster_name_present(self):
        self.assertIn("es.cluster.name", self.by_otel)

    def test_es_x_opaque_id_present(self):
        self.assertIn("es.x-opaque-id", self.by_otel)

    def test_es_task_id_present(self):
        self.assertIn("es.task.id", self.by_otel)

    def test_es_task_parent_id_present(self):
        self.assertIn("es.task.parent.id", self.by_otel)

    def test_http_flavour_present(self):
        self.assertIn("http.flavour", self.by_otel)

    def test_http_request_header_content_type_present(self):
        # APM replaces ALL separators (dots AND hyphens) with underscores in label keys, so
        # content-type → content_type and content.type → content_type are indistinguishable.
        # The mapper restores underscores to dots, producing "content.type" not "content_type".
        self.assertIn("http.request.headers.content.type", self.by_otel)

    def test_http_request_header_accept_present(self):
        self.assertIn("http.request.headers.accept", self.by_otel)

    def test_http_response_header_content_type_present(self):
        self.assertIn("http.response.headers.content.type", self.by_otel)

    # --- standard APM field mappings ---

    def test_http_method_present(self):
        self.assertIn("http.method", self.by_otel)
        self.assertEqual(self.by_otel["http.method"].apm_field, "http.request.method")

    def test_http_status_code_present(self):
        self.assertIn("http.status_code", self.by_otel)
        self.assertEqual(self.by_otel["http.status_code"].apm_field, "http.response.status_code")

    def test_http_url_present(self):
        self.assertIn("http.url", self.by_otel)
        self.assertEqual(self.by_otel["http.url"].apm_field, "url.full")

    # --- infrastructure fields are excluded ---

    def test_agent_fields_excluded(self):
        otel_names = {a.otel_name for a in self.attributes}
        self.assertNotIn("agent.name", otel_names)
        self.assertNotIn("agent.version", otel_names)

    def test_service_fields_excluded(self):
        otel_names = {a.otel_name for a in self.attributes}
        self.assertNotIn("service.name", otel_names)
        self.assertNotIn("service.version", otel_names)

    def test_data_stream_fields_excluded(self):
        otel_names = {a.otel_name for a in self.attributes}
        for name in otel_names:
            self.assertFalse(name.startswith("data_stream."), f"data_stream field leaked: {name}")

    def test_timestamp_excluded(self):
        otel_names = {a.otel_name for a in self.attributes}
        self.assertNotIn("@timestamp", otel_names)

    # --- attribute metadata ---

    def test_label_attributes_marked_is_label(self):
        self.assertTrue(self.by_otel["es.node.name"].is_label)
        self.assertTrue(self.by_otel["http.flavour"].is_label)

    def test_standard_attributes_not_marked_is_label(self):
        self.assertFalse(self.by_otel["http.method"].is_label)
        self.assertFalse(self.by_otel["http.url"].is_label)

    def test_known_attributes_flagged_is_known(self):
        for attr_name in ["es.node.name", "es.cluster.name", "http.method", "http.url"]:
            self.assertTrue(
                self.by_otel[attr_name].is_known,
                f"{attr_name} should be known",
            )


class TestCoverageReport(unittest.TestCase):
    """Coverage report correctly computes matched/missing/unexpected."""

    @classmethod
    def setUpClass(cls):
        field_caps = load_field_caps("field_caps_pre_migration.json")
        cls.attributes = process_field_caps(field_caps)

    def test_all_expected_matched(self):
        expected = {
            "es.node.name": "labels.es_node_name",
            "es.cluster.name": "labels.es_cluster_name",
            "es.x-opaque-id": "labels.es_x_opaque_id",
            "es.task.id": "labels.es_task_id",
            "es.task.parent.id": "labels.es_task_parent_id",
            "http.method": "http.request.method",
            "http.status_code": "http.response.status_code",
            "http.url": "url.full",
        }
        cov = coverage_report(self.attributes, expected)
        self.assertEqual(cov["missing"], [], f"Missing attributes: {cov['missing']}")

    def test_missing_attribute_detected(self):
        expected = {"nonexistent.attribute": "some.field"}
        cov = coverage_report(self.attributes, expected)
        self.assertIn("nonexistent.attribute", cov["missing"])

    def test_unexpected_attributes_reported(self):
        # with an empty expected set, everything found is unexpected
        cov = coverage_report(self.attributes, {})
        self.assertGreater(len(cov["unexpected"]), 0)


class TestRunFromFile(unittest.TestCase):
    """run_from_file() produces a report without errors."""

    def test_run_from_file_produces_report(self):
        import yaml
        config_path = SCRIPT_DIR / "config.yaml"
        with open(config_path) as f:
            config = yaml.safe_load(f)

        fixture = TEST_DATA_DIR / "field_caps_pre_migration.json"
        # Should not raise
        # We capture stdout to keep test output clean
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            run_from_file("qa", config, fixture)

        output = buf.getvalue()
        self.assertIn("Span attributes identified:", output)
        self.assertIn("SUMMARY", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
