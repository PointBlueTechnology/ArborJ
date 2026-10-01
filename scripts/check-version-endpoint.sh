#!/usr/bin/env bash
# Smoke-test the ArborJ update-check backend.
# Exits 0 on success, non-zero on any failure (HTTP error, malformed JSON,
# missing required fields, unexpected update_available flag, etc).
#
# Usage: scripts/check-version-endpoint.sh [current_version]
#   current_version defaults to 1.3.4

set -euo pipefail

URL_BASE="https://arborj-downloads.jcombs.workers.dev/version/arborj"
CURRENT_VERSION="${1:-1.3.4}"
URL="${URL_BASE}?v=${CURRENT_VERSION}"

command -v curl >/dev/null || { echo "curl not installed" >&2; exit 2; }
command -v jq   >/dev/null || { echo "jq not installed"   >&2; exit 2; }

echo "GET ${URL}"
body="$(curl -fsS --max-time 10 "${URL}")" || {
    echo "FAIL: HTTP request failed" >&2
    exit 1
}

echo "${body}" | jq . >/dev/null || {
    echo "FAIL: response is not valid JSON" >&2
    echo "${body}" >&2
    exit 1
}

# Required top-level fields.
for field in update_available latest downloads; do
    echo "${body}" | jq -e "has(\"${field}\")" >/dev/null || {
        echo "FAIL: response missing required field '${field}'" >&2
        echo "${body}" >&2
        exit 1
    }
done

# update_available must be boolean.
echo "${body}" | jq -e '.update_available | type == "boolean"' >/dev/null || {
    echo "FAIL: update_available is not a boolean" >&2
    exit 1
}

# downloads must contain per-platform URLs.
for platform in mac windows linux; do
    echo "${body}" | jq -e ".downloads | has(\"${platform}\")" >/dev/null || {
        echo "FAIL: downloads missing '${platform}'" >&2
        exit 1
    }
done

latest="$(echo "${body}" | jq -r '.latest')"
update_available="$(echo "${body}" | jq -r '.update_available')"
echo "OK: latest=${latest} update_available=${update_available} (queried as v${CURRENT_VERSION})"
