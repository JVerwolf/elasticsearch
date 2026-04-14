"""
Elasticsearch client for the OTel trace migration scanner.

Connects directly to an Elasticsearch cluster (the overview cluster at
overview.qa.cld.elstc.co in QA).  The overview cluster has CCS configured
to all remote clusters, so cross-cluster queries work transparently.

NOTE: Despite the module name (kept for compatibility), this talks directly
to ES, not through a Kibana console proxy.  The overview URL turned out to be
an ES endpoint — the /api/console/proxy approach was rejected because that
Kibana feature is disabled on the overview instance.
"""

from __future__ import annotations

import requests

DEFAULT_TIMEOUT = 60  # seconds


class KibanaClient:
    """
    Thin ES REST client.  Named KibanaClient for import compatibility;
    it now speaks directly to Elasticsearch.
    """

    def __init__(self, kibana_url: str, api_key: str, timeout: int = DEFAULT_TIMEOUT):
        self.kibana_url = kibana_url.rstrip("/")  # the ES base URL
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Authorization": f"ApiKey {api_key}",
                "Content-Type": "application/json",
            }
        )

    # ------------------------------------------------------------------
    # Raw ES helpers
    # ------------------------------------------------------------------

    def es_get(self, es_path: str) -> dict:
        url = f"{self.kibana_url}/{es_path.lstrip('/')}"
        resp = self.session.get(url, timeout=self.timeout)
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason} — {resp.text[:300]}",
                response=resp,
            )
        return resp.json()

    def es_post(self, es_path: str, body: dict) -> dict:
        url = f"{self.kibana_url}/{es_path.lstrip('/')}"
        resp = self.session.post(url, json=body, timeout=self.timeout)
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason} — {resp.text[:300]}",
                response=resp,
            )
        return resp.json()

    # ------------------------------------------------------------------
    # Cluster discovery
    # ------------------------------------------------------------------

    def get_local_cluster_info(self) -> dict:
        return self.es_get("_cluster/health")

    def get_remote_clusters(self) -> dict[str, dict]:
        """
        Return CCS remote clusters via GET _remote/info.
        Raises on failure so the caller surfaces the error.
        """
        return self.es_get("_remote/info")

    def discover_cluster_names(self) -> list[str | None]:
        """
        Return a list of cluster identifiers to scan.

        None  → local cluster (no CCS prefix needed)
        str   → remote cluster name (e.g. "monitor-aws-eu-west-1")

        Only connected remote clusters are included.
        """
        clusters: list[str | None] = [None]
        remote_info = self.get_remote_clusters()
        for name, info in remote_info.items():
            if info.get("connected", False):
                clusters.append(name)
        return clusters

    # ------------------------------------------------------------------
    # ES query wrappers (cluster-aware)
    # ------------------------------------------------------------------

    def _index_path(self, index_pattern: str, cluster: str | None) -> str:
        if cluster:
            return f"{cluster}:{index_pattern}"
        return index_pattern

    def field_caps(self, index_pattern: str, cluster: str | None = None) -> dict:
        """Call _field_caps, returning only fields with actual data."""
        path = f"{self._index_path(index_pattern, cluster)}/_field_caps?include_empty_fields=false"
        result = self.es_get(path)
        return result.get("fields", {})

    def count(self, index_pattern: str, cluster: str | None = None) -> int:
        path = f"{self._index_path(index_pattern, cluster)}/_count"
        result = self.es_get(path)
        return result.get("count", 0)
