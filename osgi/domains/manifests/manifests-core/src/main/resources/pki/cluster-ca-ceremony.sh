#!/usr/bin/env bash
# @codebase
# Deterministic cluster-CA ceremony — mint the rke2 bring-your-own-CA set rooted on the operator's
# existing ndh TLS root (mammoth-skate-tls), and emit it as a sops-encrypted bundle on stdout.
#
# HOST-SIDE, operator-gated, run ONCE per cluster (the CA must be STABLE across re-grows: this mints
# fresh random intermediates, so re-running yields a DIFFERENT CA — the caller/seed is responsible
# for calling it only when the cellar has no bundle yet). The root PRIVATE key never leaves the host:
# it is decrypted into a temp dir that is scrubbed on exit, and neither the root nor the generated
# intermediate private key is placed in the emitted bundle (only the five leaf CAs + service.key).
#
# The bundle it prints is the exact set nixos/sops.nix declares (11 flat keys): a node's
# rke2lab-sops-fetch lands it at /run and sops-install-secrets lays each file under the rke2
# server/tls dir before rke2-server. See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
#
# Usage:
#   cluster-ca-ceremony.sh <cluster>          # <cluster> is for logging/labelling only
# Environment:
#   NDH_KEYS_YAML       (required)  path to the sops-encrypted ndh keystore (.ndh-ssh.d/keys.yaml)
#   SOPS_AGE_KEY_FILE   (optional)  operator age key sops uses to DECRYPT the keystore
#   PKI_AUTHORITY       (=mammoth-skate-tls)  the tls-authority to root the cluster CA on
#   PKI_AGE_RECIPIENTS  (=operator,cluster)   comma-separated age recipients the bundle is sealed for
#   GEN_SCRIPT          (=<dir>/generate-custom-ca-certs.sh)  the vendored k3s generator

set -euo pipefail

log() { printf '[cluster-ca-ceremony] %s\n' "$*" >&2; }
die() {
  printf '[cluster-ca-ceremony] ERROR: %s\n' "$*" >&2
  exit 1
}

cluster="${1:-}"
[[ -n "$cluster" ]] || die "usage: cluster-ca-ceremony.sh <cluster>"

: "${NDH_KEYS_YAML:?set NDH_KEYS_YAML to the sops-encrypted .ndh-ssh.d/keys.yaml}"
authority="${PKI_AUTHORITY:-mammoth-skate-tls}"
# Default recipients: operator (git sops filter) + cluster/Flux (rke2-cluster ssh-to-age). Mirrors
# the repo-root .sops.yaml creation_rules; a caller may override for a different pair.
recipients="${PKI_AGE_RECIPIENTS:-age10ey0lcup4zpjqcknpxw7enpsagn674nm634f2u75trfr5t62uq5qdjuxzv,age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak}"
gen="${GEN_SCRIPT:-$(cd "$(dirname "$0")" && pwd)/generate-custom-ca-certs.sh}"

for tool in sops ssh-keygen openssl; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool not on PATH: $tool"
done
[[ -f "$gen" ]] || die "vendored generator not found: $gen"
[[ -f "$NDH_KEYS_YAML" ]] || die "keystore not found: $NDH_KEYS_YAML"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT INT TERM
tls="$stage/server/tls"
mkdir -p "$tls/etcd"

log "cluster=$cluster authority=$authority — extracting root of trust"
# Extract ONLY the two fields we need, each straight from sops — the full decrypted keystore (ALL of
# ndh's private material) never touches disk. ca_crt (a plaintext cert) becomes root-ca.pem; private
# (an OpenSSH ecdsa key — what step-cli signed the cert with) becomes root-ca.key, bridged to PEM
# below because the k3s generator signs with openssl, which cannot read the OpenSSH format.
extract() {
  sops --input-type yaml -d --extract "[\"authorities\"][\"$authority\"][\"$1\"]" "$NDH_KEYS_YAML"
}
extract ca_crt >"$tls/root-ca.pem" ||
  die "sops --extract ca_crt failed (SOPS_AGE_KEY_FILE? keystore decryptable? authority $authority present?)"
extract private >"$tls/root-ca.key" ||
  die "sops --extract private failed for authority $authority"
grep -q "BEGIN CERTIFICATE" "$tls/root-ca.pem" ||
  die "authority $authority has no ca_crt (run authority-bootstrap-tls-root.sh in nix-darwin-home first)"
[[ -s "$tls/root-ca.key" ]] || die "authority $authority has no private key material"
chmod 600 "$tls/root-ca.key"
if head -1 "$tls/root-ca.key" | grep -q "BEGIN OPENSSH PRIVATE KEY"; then
  log "converting root key OpenSSH -> PEM for openssl"
  ssh-keygen -p -f "$tls/root-ca.key" -m PEM -N "" -P "" >/dev/null
fi

log "minting cluster CA set (rke2 generate-custom-ca-certs.sh, rooted on $authority)"
PRODUCT=rke2 DATA_DIR="$stage" bash "$gen" >&2 || die "generate-custom-ca-certs.sh failed"

# Assemble the node bundle: the five leaf CAs (crt+key) + the service-account issuer key. The root
# and intermediate PRIVATE keys are deliberately NOT included — they stay on the operator's host.
# Flat keys matching nixos/sops.nix; etcd leaves carry an etcd- prefix (the node re-nests to etcd/).
emit() {
  printf '%s: |\n' "$1"
  sed 's/^/  /' "$tls/$2"
  # guarantee the block scalar is newline-terminated: a source file without a trailing newline would
  # otherwise concatenate the next key onto its last line and break the YAML.
  [ -z "$(tail -c1 "$tls/$2")" ] || printf '\n'
}
# Assemble the plaintext bundle and pipe it STRAIGHT into sops — the clear bundle (the five leaf
# private keys + service.key) never lands on disk; only the sealed form leaves, on stdout.
log "sealing bundle for recipients ($(echo "$recipients" | tr ',' ' ' | wc -w | tr -d ' ') age keys)"
{
  emit "server-ca.crt" server-ca.crt
  emit "server-ca.key" server-ca.key
  emit "client-ca.crt" client-ca.crt
  emit "client-ca.key" client-ca.key
  emit "request-header-ca.crt" request-header-ca.crt
  emit "request-header-ca.key" request-header-ca.key
  emit "etcd-peer-ca.crt" etcd/peer-ca.crt
  emit "etcd-peer-ca.key" etcd/peer-ca.key
  emit "etcd-server-ca.crt" etcd/server-ca.crt
  emit "etcd-server-ca.key" etcd/server-ca.key
  emit "service.key" service.key
} | sops --encrypt --config /dev/null --age "$recipients" --input-type yaml --output-type yaml /dev/stdin
log "done — sops bundle on stdout; temp material scrubbed on exit"
