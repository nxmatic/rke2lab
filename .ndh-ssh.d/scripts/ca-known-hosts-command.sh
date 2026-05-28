#!/usr/bin/env bash
# KnownHostsCommand script (@codebase)
# Emits host CA certificate lines for dynamic known hosts resolution.
# Template variable @caDir@ is replaced by Nix with the derivation output directory
# holding extracted CA public keys (files matching *-ca.pub).

set -euo pipefail

# shellcheck disable=SC1091
source "@nixBashTrampoline@"

main() {
  mode_or_host="${1:-}"
  case "${mode_or_host}" in
    ORDER|HOSTNAME|ADDRESS)
      # OpenSSH can invoke KnownHostsCommand with an intent token first.
      host="${2:-}"
      ip="${3:-}"
      ;;
    *)
      host="${1:-}"   # Traditional invocation: host [ip]
      ip="${2:-}"
      ;;
  esac

  CA_DIR="@caDir@"

  declare -A seen=()

  emit_line() {
    local line="$1"
    [[ -z "${line}" ]] && return
    if [[ -n "${seen[${line}]:-}" ]]; then
      return
    fi
    echo "${line}"
    seen["${line}"]=1
  }

  trim_domain() {
    local raw="$1"
    printf '%s' "${raw}" | tr -d '\r' | sed -e 's/^\s\+//' -e 's/\s\+$//'
  }

  shopt -s nullglob 2>/dev/null || true
  for f in "${CA_DIR}"/*-ca.pub; do
    [[ -f "${f}" ]] || continue

    key_line="$(sed -n '1p' "${f}" || true)"
    key_type="$(printf '%s\n' "${key_line}" | awk '{print $1}')"
    key_data="$(printf '%s\n' "${key_line}" | awk '{print $2}')"
    case "${key_type}" in
      ssh-*|ecdsa-*|sk-*) ;;
      *) continue ;;
    esac
    [[ -n "${key_data}" ]] || continue
    key_part="${key_type} ${key_data}"
    base="${f%-ca.pub}"
    domain=""
    if [[ -f "${base}-ca.domain" ]]; then
      domain_raw="$(<"${base}-ca.domain")"
      domain="$(trim_domain "${domain_raw}")"
    fi

    if [[ -n "${domain}" ]]; then
      emit_line "@cert-authority *.${domain} ${key_part}"
    else
      emit_line "@cert-authority * ${key_part}"
    fi

    if [[ -n "${host}" ]]; then
      emit_line "@cert-authority ${host} ${key_part}"
    fi
  done

  return 0
}

ndh::logger:command:run ssh.known-hosts-command main "$@"
