"""
Thin wrapper around the Elasticsearch REST API for the trace scanner.
"""

import requests
from typing import Any

DEFAULT_TIMEOUT = 60  # seconds


class ElasticsearchClient:
    def __init__(self, es_url: str, headers: dict[str, str], timeout: int = DEFAULT_TIMEOUT):
        self.es_url = es_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update(headers)
        self.session.headers.update({"Content-Type": "application/json"})
        self.timeout = timeout

    def get(self, path: str, **params) -> Any:
        url = f"{self.es_url}/{path.lstrip('/')}"
        resp = self.session.get(url, params=params, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def post(self, path: str, body: dict) -> Any:
        url = f"{self.es_url}/{path.lstrip('/')}"
        resp = self.session.post(url, json=body, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def field_caps(self, index_pattern: str, fields: str = "*") -> dict:
        """
        Call the _field_caps API to discover which fields exist (and have data)
        in the given index pattern.

        Returns a dict: field_name -> {type -> {indices, searchable, aggregatable, ...}}
        """
        result = self.get(f"{index_pattern}/_field_caps", fields=fields, include_empty_fields="false")
        return result.get("fields", {})

    def mapping(self, index_pattern: str) -> dict:
        """Return the full mapping for the index pattern."""
        return self.get(f"{index_pattern}/_mapping")

    def count(self, index_pattern: str, query: dict | None = None) -> int:
        """Return the document count matching an optional query."""
        body: dict = {}
        if query:
            body["query"] = query
        result = self.post(f"{index_pattern}/_count", body)
        return result.get("count", 0)

    def search(self, index_pattern: str, body: dict) -> dict:
        return self.post(f"{index_pattern}/_search", body)

    def aggregate_label_keys(self, index_pattern: str, service_filter: str | None = None, size: int = 500) -> list[str]:
        """
        Discover all label keys actually present in the data.

        APM stores OTel custom attributes as `labels.<key>` where dots and
        hyphens in the original key are replaced with underscores.

        We find these by using _field_caps (filtered to labels.*) rather than
        an aggregation, since the keys are baked into the mapping.
        """
        fields = self.field_caps(index_pattern, fields="labels.*")
        return sorted(fields.keys())

    def get_cluster_info(self) -> dict:
        """Return basic cluster info (name, version, etc.)."""
        return self.get("/")
