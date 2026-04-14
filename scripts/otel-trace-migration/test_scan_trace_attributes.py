"""
Unit tests for scan_trace_attributes.py (pure logic, no network).

Run with:  python -m pytest test_scan_trace_attributes.py -v
Or:        python test_scan_trace_attributes.py
"""

import sys
import unittest

from scan_trace_attributes import (
    AttributeInfo,
    classify_field,
    coverage_report,
    is_apm_infrastructure_field,
    label_key_to_otel,
    process_field_caps,
)


class TestLabelKeyToOtel(unittest.TestCase):
    def test_known_es_attributes(self):
        self.assertEqual(label_key_to_otel("es_node_name"), "es.node.name")
        self.assertEqual(label_key_to_otel("es_cluster_name"), "es.cluster.name")
        self.assertEqual(label_key_to_otel("es_x_opaque_id"), "es.x-opaque-id")
        self.assertEqual(label_key_to_otel("es_task_id"), "es.task.id")
        self.assertEqual(label_key_to_otel("es_task_parent_id"), "es.task.parent.id")

    def test_http_flavour(self):
        self.assertEqual(label_key_to_otel("http_flavour"), "http.flavour")

    def test_request_header_prefix(self):
        self.assertEqual(
            label_key_to_otel("http_request_headers_content_type"),
            "http.request.headers.content.type",
        )
        self.assertEqual(
            label_key_to_otel("http_request_headers_accept"),
            "http.request.headers.accept",
        )

    def test_response_header_prefix(self):
        self.assertEqual(
            label_key_to_otel("http_response_headers_content_length"),
            "http.response.headers.content.length",
        )

    def test_otel_attributes_prefix(self):
        # APM agent wraps unknown OTel attrs in otel.attributes.*
        self.assertEqual(
            label_key_to_otel("otel_attributes_db_statement"),
            "otel.attributes.db.statement",
        )

    def test_unknown_label_fallback(self):
        # Unknown labels get underscores replaced with dots (best effort)
        result = label_key_to_otel("some_custom_label")
        self.assertEqual(result, "some.custom.label")


class TestClassifyField(unittest.TestCase):
    def test_label_es_node_name(self):
        attr = classify_field("labels.es_node_name", "keyword")
        self.assertIsNotNone(attr)
        self.assertEqual(attr.otel_name, "es.node.name")
        self.assertTrue(attr.is_label)
        self.assertTrue(attr.is_known)

    def test_label_unknown(self):
        attr = classify_field("labels.my_custom_thing", "keyword")
        self.assertIsNotNone(attr)
        self.assertFalse(attr.is_known)
        self.assertTrue(attr.is_label)

    def test_standard_http_method(self):
        attr = classify_field("http.request.method", "keyword")
        self.assertIsNotNone(attr)
        self.assertEqual(attr.otel_name, "http.method")
        self.assertFalse(attr.is_label)
        self.assertTrue(attr.is_known)

    def test_standard_url_full(self):
        attr = classify_field("url.full", "keyword")
        self.assertIsNotNone(attr)
        self.assertEqual(attr.otel_name, "http.url")

    def test_infrastructure_field_agent(self):
        self.assertIsNone(classify_field("agent.name", "keyword"))
        self.assertIsNone(classify_field("agent.version", "keyword"))

    def test_infrastructure_field_data_stream(self):
        self.assertIsNone(classify_field("data_stream.type", "keyword"))

    def test_infrastructure_field_service(self):
        self.assertIsNone(classify_field("service.name", "keyword"))

    def test_http_status_code(self):
        attr = classify_field("http.response.status_code", "long")
        self.assertIsNotNone(attr)
        self.assertEqual(attr.otel_name, "http.status_code")

    def test_span_fields_included(self):
        attr = classify_field("span.name", "keyword")
        self.assertIsNotNone(attr)

    def test_transaction_fields_included(self):
        attr = classify_field("transaction.name", "keyword")
        self.assertIsNotNone(attr)


class TestIsApmInfrastructureField(unittest.TestCase):
    def test_agent(self):
        self.assertTrue(is_apm_infrastructure_field("agent.name"))

    def test_ecs(self):
        self.assertTrue(is_apm_infrastructure_field("ecs.version"))

    def test_labels(self):
        # labels.* are NOT infrastructure — they are span attributes
        self.assertFalse(is_apm_infrastructure_field("labels.es_node_name"))

    def test_http(self):
        self.assertFalse(is_apm_infrastructure_field("http.request.method"))


class TestProcessFieldCaps(unittest.TestCase):
    def _make_field_caps(self, fields: list[tuple[str, str]]) -> dict:
        """Build a minimal _field_caps 'fields' dict for testing."""
        return {name: {ftype: {"searchable": True, "aggregatable": True}} for name, ftype in fields}

    def test_basic(self):
        caps = self._make_field_caps(
            [
                ("labels.es_node_name", "keyword"),
                ("labels.es_cluster_name", "keyword"),
                ("http.request.method", "keyword"),
                ("agent.name", "keyword"),  # should be filtered
                ("service.name", "keyword"),  # should be filtered
            ]
        )
        attrs = process_field_caps(caps)
        names = {a.otel_name for a in attrs}
        self.assertIn("es.node.name", names)
        self.assertIn("es.cluster.name", names)
        self.assertIn("http.method", names)
        self.assertNotIn("agent.name", names)
        self.assertNotIn("service.name", names)

    def test_empty(self):
        self.assertEqual(process_field_caps({}), [])


class TestCoverageReport(unittest.TestCase):
    def _make_attrs(self, otel_names: list[str]) -> list[AttributeInfo]:
        return [
            AttributeInfo(
                apm_field=f"labels.{n.replace('.', '_').replace('-', '_')}",
                otel_name=n,
                field_type="keyword",
                is_label=True,
                is_known=True,
            )
            for n in otel_names
        ]

    def test_all_matched(self):
        attrs = self._make_attrs(["es.node.name", "es.cluster.name"])
        expected = {"es.node.name": "desc", "es.cluster.name": "desc"}
        cov = coverage_report(attrs, expected)
        self.assertEqual(sorted(cov["matched"]), ["es.cluster.name", "es.node.name"])
        self.assertEqual(cov["missing"], [])
        self.assertEqual(cov["unexpected"], [])

    def test_missing(self):
        attrs = self._make_attrs(["es.node.name"])
        expected = {"es.node.name": "desc", "es.cluster.name": "desc"}
        cov = coverage_report(attrs, expected)
        self.assertIn("es.cluster.name", cov["missing"])

    def test_unexpected(self):
        attrs = self._make_attrs(["es.node.name", "custom.attr"])
        expected = {"es.node.name": "desc"}
        cov = coverage_report(attrs, expected)
        self.assertIn("custom.attr", cov["unexpected"])

    def test_wildcard_expected(self):
        # Wildcard patterns match without raising missing if nothing found
        attrs = self._make_attrs(["es.node.name"])
        expected = {
            "es.node.name": "desc",
            "http.request.headers.*": "desc",  # wildcard, ok to be absent
        }
        cov = coverage_report(attrs, expected)
        # Wildcard with no match should NOT appear in missing
        self.assertNotIn("http.request.headers.*", cov["missing"])

    def test_wildcard_match(self):
        attrs = self._make_attrs(["http.request.headers.content_type"])
        expected = {"http.request.headers.*": "desc"}
        cov = coverage_report(attrs, expected)
        self.assertIn("http.request.headers.content_type", cov["matched"])
        self.assertEqual(cov["unexpected"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
