#!/usr/bin/env bash
set -euo pipefail

require_line() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    printf 'FAIL container hardening: expected setting missing in %s\n' "$file" >&2
    exit 1
  fi
}

require_line backend/Dockerfile 'USER 10001:10001'
require_line frontend/Dockerfile 'USER 101:101'
require_line compose.yaml 'read_only: true'
require_line compose.yaml 'backend-container-tmp:/tmp'
require_line compose.yaml 'frontend-container-tmp:/tmp'
require_line compose.yaml '127.0.0.1:8080:8080'
require_line compose.yaml '127.0.0.1:4566:4566'
printf '%s\n' 'PASS non-root, read-only, and loopback container configuration'
