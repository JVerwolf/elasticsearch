"""
Authentication helpers for the OTel trace migration scanner.

API keys are stored in api-keys.json (gitignored) as a dict mapping
normalized ES cluster URLs to API key strings:

    {
      "https://overview.qa.cld.elstc.co": "VGhpcyBpcyBhIGZha2U..."
    }

The API key value should be the base64-encoded "id:api_key" string
("encoded" field) from POST /_security/api_key run in the Kibana Dev Console.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

_API_KEYS_FILE = Path(__file__).parent / "api-keys.json"


def _normalize_url(url: str) -> str:
    """Normalize a URL for use as a key in api-keys.json."""
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


def get_api_key(kibana_url: str) -> str | None:
    """Return the stored API key for the given Kibana URL, or None if not found."""
    normalized = _normalize_url(kibana_url)
    keys = load_api_keys()
    return keys.get(normalized)


def set_api_key(kibana_url: str, api_key: str) -> None:
    """Persist an API key for the given Kibana URL."""
    normalized = _normalize_url(kibana_url)
    keys = load_api_keys()
    keys[normalized] = api_key
    save_api_keys(keys)


def get_api_key_or_raise(kibana_url: str) -> str:
    """
    Return the stored API key for the given Kibana URL.

    Raises RuntimeError if no key is configured.  Directs the user to add one.
    """
    key = get_api_key(kibana_url)
    if key is None:
        raise RuntimeError(
            f"No API key found for {kibana_url}.\n"
            "Add one with:\n"
            f"  ./otm <env> add-key {kibana_url} <your-api-key>\n"
            "Create the key in Kibana → Stack Management → API Keys.\n"
            "Required ES privileges: cluster:monitor, index read+view_index_metadata on traces-apm*"
        )
    return key
