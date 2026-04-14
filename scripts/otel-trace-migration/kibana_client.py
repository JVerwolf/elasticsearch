"""
Kibana client for the OTel trace migration scanner.

All Elasticsearch queries are routed through Kibana's console proxy endpoint:
  POST /api/console/proxy?path=<url-encoded-es-path>&method=<GET|POST>

This matches exactly what the Kibana browser Dev Console sends.  The path
parameter is URL-encoded (/ → %2F, * → %2A, etc.) by requests' params= dict,
which is the format the proxy expects.

AUTHENTICATION: this endpoint requires a Kibana API key with the Dev Tools
feature privilege — an ES-only API key (created via POST /_security/api_key)
is not sufficient.  Create the key in Kibana UI:
  Stack Management → Security → API Keys → Create API key
  → Kibana privileges → Dev Tools → All (or Read)
  → Elasticsearch privileges → cluster: monitor, indices: read+view_index_metadata on traces-apm*
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
    # ES proxy helpers (routes through /api/console/proxy)
    # ------------------------------------------------------------------

    def _proxy(self, method: str, es_path: str, body: dict | None = None) -> dict:
        """
        Send an ES request through Kibana's console proxy.

        Uses requests params= so the path is URL-encoded (matching browser behavior).
        Requires a Kibana API key with Dev Tools privilege.
        """
        es_path = es_path.lstrip("/")
        url = f"{self.kibana_url}/api/console/proxy"
        params = {"path": es_path, "method": method}
        kwargs: dict = {"params": params, "timeout": self.timeout}
        if body:
            kwargs["json"] = body
        resp = self.session.post(url, **kwargs)
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason} — {resp.text[:400]}",
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
        return self.es_get("_cluster/health")

    def get_remote_clusters(self) -> dict[str, dict]:
        """Return CCS remote clusters via GET _remote/info. Raises on failure."""
        return self.es_get("_remote/info")

    def discover_cluster_names(self) -> list[str | None]:
        """
        Return cluster identifiers to scan.
        None = local cluster; str = CCS remote cluster name.
        Only connected remotes are included.
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
