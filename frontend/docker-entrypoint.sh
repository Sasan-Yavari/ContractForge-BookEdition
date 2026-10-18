#!/bin/sh
set -eu

fail() {
  echo "Frontend runtime configuration is invalid; refusing to start." >&2
  exit 1
}

json_escape() {
  case "$1" in
    *'
'*) fail ;;
  esac
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

environment="${CONTRACTFORGE_RUNTIME_ENV:-}"
mode="${CONTRACTFORGE_AUTH_MODE:-}"
case "$environment:$mode" in
  local:local)
    token="${CONTRACTFORGE_LOCAL_AUTH_TOKEN:-}"
    [ -n "$token" ] || fail
    document=$(printf '{"environment":"local","auth":{"mode":"local","token":"%s"}}' "$(json_escape "$token")")
    ;;
  Development:cognito|Production:cognito)
    issuer="${CONTRACTFORGE_COGNITO_ISSUER_URI:-}"
    domain="${CONTRACTFORGE_COGNITO_DOMAIN:-}"
    client_id="${CONTRACTFORGE_COGNITO_CLIENT_ID:-}"
    scopes="${CONTRACTFORGE_COGNITO_SCOPES:-}"
    callback_url="${CONTRACTFORGE_COGNITO_CALLBACK_URL:-}"
    logout_url="${CONTRACTFORGE_COGNITO_LOGOUT_URL:-}"
    for value in "$issuer" "$domain" "$client_id" "$scopes" "$callback_url" "$logout_url"; do
      [ -n "$value" ] || fail
    done
    case "$issuer:$domain:$callback_url:$logout_url" in https://*:https://*:https://*:https://*) ;; *) fail ;; esac
    printf '%s' "$client_id" | grep -Eq '^[A-Za-z0-9]+$' || fail
    printf '%s' "$scopes" | grep -Eq '^[A-Za-z0-9/_. -]+$' || fail
    document=$(printf '{"environment":"%s","auth":{"mode":"cognito","issuer":"%s","domain":"%s","clientId":"%s","scopes":"%s","callbackUrl":"%s","logoutUrl":"%s"}}' \
      "$(json_escape "$environment")" \
      "$(json_escape "$issuer")" \
      "$(json_escape "$domain")" \
      "$(json_escape "$client_id")" \
      "$(json_escape "$scopes")" \
      "$(json_escape "$callback_url")" \
      "$(json_escape "$logout_url")")
    ;;
  *) fail ;;
esac

configuration="/tmp/runtime-config.json"
temporary_configuration="${configuration}.tmp"
umask 022
printf '%s\n' "$document" > "$temporary_configuration"
chmod 644 "$temporary_configuration"
mv "$temporary_configuration" "$configuration"
