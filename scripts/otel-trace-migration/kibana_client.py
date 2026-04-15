"""
Elasticsearch client for the OTel trace migration scanner.

Talks directly to an ES cluster REST API using an API key.
No Kibana console proxy involved.

The cluster URLs are derived from GET _remote/info proxy_address fields:
  "8435137e1d624a1d9810728b8b382001.eu-west-1.aws.qa.cld.elstc.co:9400"
   → https://8435137e1d624a1d9810728b8b382001.eu-west-1.aws.qa.cld.elstc.co
"""

from __future__ import annotations

import requests

DEFAULT_TIMEOUT = 60  # seconds


class KibanaClient:
    """Direct ES REST client (name kept for import compatibility)."""

    def __init__(self, kibana_url: str, api_key: str, timeout: int = DEFAULT_TIMEOUT):
        self.kibana_url = kibana_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Authorization": f"ApiKey {api_key}"})

    def es_get(self, es_path: str) -> dict:
        url = f"{self.kibana_url}/{es_path.lstrip('/')}"
        resp = self.session.get(url, timeout=self.timeout)
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason} — {resp.text[:300]}",
                response=resp,
            )
        return resp.json()

    def get_local_cluster_info(self) -> dict:
        return self.es_get("_cluster/health")

    def field_caps(self, index_pattern: str, cluster: str | None = None) -> dict:
        path = f"{index_pattern}/_field_caps?include_empty_fields=false"
        result = self.es_get(path)
        return result.get("fields", {})

    def count(self, index_pattern: str, cluster: str | None = None) -> int:
        result = self.es_get(f"{index_pattern}/_count")
        return result.get("count", 0)
