#!/usr/bin/env bash
set -euo pipefail

site_headers="$(mktemp)"
runtime_headers="$(mktemp)"
trap 'rm -f "$site_headers" "$runtime_headers"' EXIT
curl --fail --silent --show-error --dump-header "$site_headers" --output /dev/null http://localhost:8080/
curl --fail --silent --show-error --dump-header "$runtime_headers" --output /dev/null http://localhost:8080/runtime-config.json
grep -qi '^content-security-policy:' "$site_headers"
grep -qi '^x-content-type-options: nosniff' "$site_headers"
grep -qi '^referrer-policy:' "$site_headers"
grep -qi '^cache-control:.*no-store' "$runtime_headers"
printf '%s\n' 'PASS loopback frontend security headers'
