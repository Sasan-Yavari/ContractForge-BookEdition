#!/bin/sh
set -eu

fail() {
  echo "Frontend Content Security Policy configuration is invalid; refusing to start." >&2
  exit 1
}

url_origin() {
  case "$1" in
    https://*) authority=${1#https://} ;;
    *) return 1 ;;
  esac

  authority=${authority%%/*}
  printf '%s' "$authority" | grep -Eq '^[A-Za-z0-9]+(-[A-Za-z0-9]+)*(\.[A-Za-z0-9]+(-[A-Za-z0-9]+)*)*$' || return 1
  printf 'https://%s' "$authority"
}

runtime_environment="${CONTRACTFORGE_RUNTIME_ENV:-}"
auth_mode="${CONTRACTFORGE_AUTH_MODE:-}"
case "$runtime_environment:$auth_mode" in
  local:local)
    upload_origin="${CONTRACTFORGE_S3_UPLOAD_ORIGIN:-}"
    [ "$upload_origin" = "http://localhost:4566" ] || fail
    connect_sources="'self' $upload_origin"
    ;;
  Development:cognito|Production:cognito)
    domain="${CONTRACTFORGE_COGNITO_DOMAIN:-}"
    issuer="${CONTRACTFORGE_COGNITO_ISSUER_URI:-}"
    upload_origin="${CONTRACTFORGE_S3_UPLOAD_ORIGIN:-}"
    [ -n "$domain" ] && [ -n "$issuer" ] && [ -n "$upload_origin" ] || fail

    domain_origin=$(url_origin "$domain") || fail
    issuer_origin=$(url_origin "$issuer") || fail
    upload_origin_from_url=$(url_origin "$upload_origin") || fail
    [ "$domain_origin" = "$domain" ] || fail
    [ "$upload_origin_from_url" = "$upload_origin" ] || fail
    issuer_path=${issuer#"$issuer_origin"/}
    [ "$issuer_path" != "$issuer" ] || fail

    printf '%s' "${domain_origin#https://}" \
      | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\.auth\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\.amazoncognito\.com$' || fail
    printf '%s' "${issuer_origin#https://}" \
      | grep -Eq '^cognito-idp\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\.amazonaws\.com$' || fail
    printf '%s' "${upload_origin#https://}" \
      | grep -Eq '^[a-z0-9][a-z0-9-]*\.s3\.[a-z0-9]([a-z0-9-]*[a-z0-9])?\.amazonaws\.com$' || fail
    printf '%s' "$issuer_path" | grep -Eq '^[A-Za-z0-9_-]+$' || fail

    connect_sources="'self' $domain_origin $issuer_origin $upload_origin"
    ;;
  *)
    fail
    ;;
esac

output="${1:-/tmp/contractforge-content-security-policy.conf}"
temporary_output="${output}.tmp"
umask 022
cat > "$temporary_output" <<EOF
map \$host \$contractforge_content_security_policy {
    default "default-src 'none'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; frame-src 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src $connect_sources;";
}
EOF
chmod 644 "$temporary_output"
mv "$temporary_output" "$output"
