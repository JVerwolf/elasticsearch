"""
Authentication helpers for the OTel trace migration scanner.

API keys are stored in api-keys.json (gitignored) as a dict mapping
normalized ES URLs to API key strings:

    {
      "https://my-cluster.es.us-central1.gcp.qa.cld.elstc.co:9243": "VGhpcyBpcyBhIGZha2U..."
    }

The API key value should be the base64-encoded "id:api_key" string
suitable for use in the "Authorization: ApiKey <value>" header.
"""

import json
import os
from pathlib import Path

_API_KEYS_FILE = Path(__file__).parent / "api-keys.json"


def _normalize_url(url: str) -> str:
    """Normalize an ES URL for use as a key in api-keys.json."""
    url = url.strip().rstrip("/")
    if "://" not in url:
        url = "https://" + url
    scheme, rest = url.split("://", 1)
    return scheme.lower() + "://" + rest.lower()


def load_api_keys() -> dict[str, str]:
    """Load stored API keys from api-keys.json, returning {} if file absent."""
    if not _API_KEYS_FILE.exists():
        return {}
    try:
        with open(_API_KEYS_FILE) as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}


def save_api_keys(keys: dict[str, str]) -> None:
    with open(_API_KEYS_FILE, "w") as f:
        json.dump(keys, f, indent=2)
    os.chmod(_API_KEYS_FILE, 0o600)


def get_api_key(es_url: str) -> str | None:
    """Return the stored API key for the given ES URL, or None if not found."""
    normalized = _normalize_url(es_url)
    keys = load_api_keys()
    return keys.get(normalized)


def set_api_key(es_url: str, api_key: str) -> None:
    """Persist an API key for the given ES URL."""
    normalized = _normalize_url(es_url)
    keys = load_api_keys()
    keys[normalized] = api_key
    save_api_keys(keys)


def auth_headers(es_url: str) -> dict[str, str]:
    """
    Return HTTP headers for authenticating to the given ES cluster.

    Raises RuntimeError if no API key is configured for this cluster.
    Prompt the user to add one with:
        python otm.py <env> add-key <es_url> <api_key>
    """
    key = get_api_key(es_url)
    if key is None:
        raise RuntimeError(
            f"No API key found for {es_url}.\n"
            "Add one with:\n"
            f"  ./otm <env> add-key {es_url} <your-api-key>\n"
            "The API key should be the base64-encoded 'id:key' string "
            "from Kibana → Stack Management → API Keys."
        )
    return {"Authorization": f"ApiKey {key}"}
