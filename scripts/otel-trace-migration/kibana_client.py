"""
Kibana client for the OTel trace migration scanner.

All Elasticsearch queries are routed through Kibana's console proxy endpoint:
  POST /api/console/proxy?path=<es-path>&method=<GET|POST>

IMPORTANT: the console proxy rejects paths that begin with '/'.
All es_path values must be passed WITHOUT a leading slash.

This means one Kibana API key is sufficient for the entire scan — no separate
ES API key is needed.  Cross-cluster search (CCS) is handled transparently:
remote cluster names are prefixed onto index patterns automatically, and
Kibana/ES handles the routing.
"""

from __future__ import annotations

import requests

DEFAULT_TIMEOUT = 60  # seconds


class KibanaClient:
    def __init__(self, kibana_url: str, api_key: str, timeout: int = DEFAULT_TIMEOUT):
        self.kibana_url = kibana_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Authorization": f"ApiKey {api_key}",
                "kbn-xsrf": "true",
                "Content-Type": "application/json",
            }
        )

    # ------------------------------------------------------------------
    # Kibana API helpers
    # ------------------------------------------------------------------

    def kibana_get(self, path: str) -> dict:
        url = f"{self.kibana_url}/{path.lstrip('/')}"
        resp = self.session.get(url, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    # ------------------------------------------------------------------
    # ES proxy helpers (routes through /api/console/proxy)
    # ------------------------------------------------------------------

    def _proxy(self, method: str, es_path: str, body: dict | None = None) -> dict:
        """
        Send an ES request through Kibana's console proxy.

        es_path must NOT begin with '/'; the proxy returns 400 if it does.

        We build the query string manually rather than using requests' params=
        because requests URL-encodes the path value (/ → %2F, * → %2A, etc.)
        and the console proxy requires literal slashes and wildcards in the path.
        """
        es_path = es_path.lstrip("/")
        url = f"{self.kibana_url}/api/console/proxy?path={es_path}&method={method}"
        kwargs: dict = {"timeout": self.timeout}
        if body:
            kwargs["json"] = body
        resp = self.session.post(url, **kwargs)
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason} for url: {resp.url}\nResponse body: {resp.text[:500]}",
                response=resp,
            )
        return resp.json()

    def es_get(self, es_path: str) -> dict:
        return self._proxy("GET", es_path)

    def es_post(self, es_path: str, body: dict) -> dict:
        return self._proxy("POST", es_path, body)

    # ------------------------------------------------------------------
    # Cluster discovery
    # ------------------------------------------------------------------

    def get_local_cluster_info(self) -> dict:
        """
        Return basic info about the local ES cluster Kibana is connected to.

        Uses _cluster/health (not GET /) because the console proxy rejects '/'.
        Returns cluster_name and status; version is not available this way.
        """
        return self.es_get("_cluster/health")

    def get_remote_clusters(self) -> dict[str, dict]:
        """
        Return info about CCS remote clusters via GET _remote/info.

        Keys are remote cluster names; values contain connection state.
        Raises on failure so callers can surface the error rather than
        silently treating it as "no remotes".
        """
        return self.es_get("_remote/info")

    def discover_cluster_names(self) -> list[str | None]:
        """
        Return a list of cluster identifiers to scan.

        None  → local cluster (no CCS prefix needed)
        str   → remote cluster name (used as CCS prefix, e.g. "remote1:traces-apm*")

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
        """Build an index path, prepending the CCS cluster prefix if given."""
        if cluster:
            return f"{cluster}:{index_pattern}"
        return index_pattern

    def field_caps(self, index_pattern: str, cluster: str | None = None) -> dict:
        """
        Call _field_caps and return the 'fields' dict.

        include_empty_fields=false means only fields with actual data are returned,
        which keeps the report focused on what's actually present.
        """
        path = f"{self._index_path(index_pattern, cluster)}/_field_caps?include_empty_fields=false"
        result = self.es_get(path)
        return result.get("fields", {})

    def count(self, index_pattern: str, cluster: str | None = None) -> int:
        """Return the document count in the given index pattern."""
        path = f"{self._index_path(index_pattern, cluster)}/_count"
        result = self.es_get(path)
        return result.get("count", 0)
