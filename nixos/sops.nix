# sops-nix on the homogeneous substrate — the DECLARATION is baked, the per-cluster DATA arrives at
# runtime. The image never carries a PKI: sops.secrets names the cluster-CA files and their target
# paths under the rke2 server tls dir (build-time, identical on every node), while the encrypted
# bundle and the age identity are delivered over devlxd at boot (rke2lab-sops-fetch, below) into /run.
# validateSopsFiles=false lets defaultSopsFile be that runtime path; sops-install-secrets decrypts on
# the node and lays the CA set down before rke2-server, which then issues every leaf from it.
# See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
{
  lib,
  pkgs,
  ...
}:
let
  tls = "/var/lib/rancher/rke2/server/tls";
  # bundle YAML key -> path under the rke2 server tls dir. The bundle is flat (etcd leaf CAs carry an
  # etcd- prefix); rke2 wants them under an etcd/ subdir, so the path re-nests. sops-install-secrets
  # splits `key` on '/', so the flat prefixed names stay single-level lookups.
  caFile = key: path: {
    inherit key path;
    owner = "root";
    group = "root";
    mode = "0600";
  };
in
{
  sops = {
    # The age identity and the bundle are delivered at runtime (devlxd) — never baked, never derived
    # from an ssh host key. Disable both auto-derivation paths.
    age.keyFile = "/run/rke2lab/sops-age.key";
    age.sshKeyPaths = [ ];
    gnupg.sshKeyPaths = [ ];

    # The bundle does not exist at build (it is a runtime path): skip the build-time store/existence
    # check, and run sops-install-secrets as a systemd service rather than an early activation script
    # (which would precede the devlxd delivery).
    validateSopsFiles = false;
    useSystemdActivation = true;
    defaultSopsFile = "/run/rke2lab/cluster-ca-bundle.yaml";

    # The bring-your-own-CA set rke2 finds under server/tls before first boot: five leaf CAs
    # (each crt+key) plus the service-account issuer key. Placed here, rke2 skips CA generation and
    # issues every leaf (apiserver, kubelet, etcd, SA) from these — rooting the cluster on our CA.
    secrets = {
      "server-ca.crt" = caFile "server-ca.crt" "${tls}/server-ca.crt";
      "server-ca.key" = caFile "server-ca.key" "${tls}/server-ca.key";
      "client-ca.crt" = caFile "client-ca.crt" "${tls}/client-ca.crt";
      "client-ca.key" = caFile "client-ca.key" "${tls}/client-ca.key";
      "request-header-ca.crt" = caFile "request-header-ca.crt" "${tls}/request-header-ca.crt";
      "request-header-ca.key" = caFile "request-header-ca.key" "${tls}/request-header-ca.key";
      "etcd-peer-ca.crt" = caFile "etcd-peer-ca.crt" "${tls}/etcd/peer-ca.crt";
      "etcd-peer-ca.key" = caFile "etcd-peer-ca.key" "${tls}/etcd/peer-ca.key";
      "etcd-server-ca.crt" = caFile "etcd-server-ca.crt" "${tls}/etcd/server-ca.crt";
      "etcd-server-ca.key" = caFile "etcd-server-ca.key" "${tls}/etcd/server-ca.key";
      "service.key" = caFile "service.key" "${tls}/service.key";
    };
  };

  # sops-install-secrets writes each secret to its target path; ensure the rke2 tls dirs exist (it
  # MkdirAll's the parent, but the etcd/ subdir + strict perms are declared here so the layout is
  # explicit and owned before first boot).
  systemd.tmpfiles.rules = [
    "d /var/lib/rancher/rke2/server/tls 0700 root root - -"
    "d /var/lib/rancher/rke2/server/tls/etcd 0700 root root - -"
  ];

  # devlxd delivery of the sops material into /run, BEFORE sops-install-secrets — mirrors
  # nix-darwin-home's sops-age-bootstrap ordering (modules/nixos/sops.nix): the key/material provider
  # runs before, and is pulled in by, sops-install-secrets. Early-safe (DefaultDependencies=no,
  # /dev/incus/sock present from container start), so it runs in the sysinit phase alongside
  # sops-install-secrets. Both fetches are OPTIONAL: a node grown without a cluster-CA projection
  # boots without them (rke2 self-generates its CA), so a missing key/bundle must not fail this unit.
  systemd.services.rke2lab-sops-fetch = {
    description = "rke2lab sops material (devlxd → /run: age key + cluster-CA bundle)";
    before = [ "sops-install-secrets.service" ];
    wantedBy = [ "sops-install-secrets.service" ];
    after = [ "local-fs.target" ];
    unitConfig.DefaultDependencies = "no";
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [ pkgs.curl ];
    script = ''
      set -euo pipefail
      sock=/dev/incus/sock
      install -d -m 0755 /run/rke2lab
      fetch() {
        curl -sf --unix-socket "$sock" "http://x/1.0/config/user.rke2lab.$1" 2>/dev/null || return 1
      }
      umask 077
      if key="$(fetch sops-age-key)"; then
        printf '%s' "$key" >/run/rke2lab/sops-age.key
        chmod 0400 /run/rke2lab/sops-age.key
      fi
      if bundle="$(fetch cluster-ca-bundle)"; then
        printf '%s' "$bundle" >/run/rke2lab/cluster-ca-bundle.yaml
        chmod 0400 /run/rke2lab/cluster-ca-bundle.yaml
      fi
    '';
  };

  # sops-install-secrets waits for the devlxd delivery, then runs before rke2-server. The tie to
  # rke2-server is WEAK (wantedBy, not requiredBy): if the bundle is absent or undecryptable,
  # sops-install-secrets fails but rke2 still starts and self-generates its CA — the
  # pre-deterministic-PKI fallback, surfaced as a failed unit the rke2lab.target probe reports.
  systemd.services.sops-install-secrets = {
    after = [ "rke2lab-sops-fetch.service" ];
    requires = [ "rke2lab-sops-fetch.service" ];
    before = [ "rke2-server.service" ];
    wantedBy = [ "rke2-server.service" ];
  };
}
