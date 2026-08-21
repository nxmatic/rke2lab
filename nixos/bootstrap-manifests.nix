# The node-side bootstrap server-manifests seed — the one lane that lands cluster-level bootstrap
# objects on the node at first boot, now that cloud-init and the /srv/host mount are gone. RKE2
# auto-deploys any YAML under server/manifests/ before the kubelet is ready; this oneshot fetches the
# rendered bootstrap subset over devlxd and writes it there, so it is present when rke2-server starts.
#
# WHY devlxd, not baked: the image is homogeneous — the bootstrap subset is per-cluster (Flux tracks
# THIS cluster's rendered branch manifests/<host>-<role>, the sops-age + githubapp Secrets are this
# cluster's). The host GROW poses it on `user.rke2lab.server-manifests` (a multi-document YAML string;
# the subset is a handful of small CRs — Flux operator/instance/root + the two bootstrap Secrets + the
# cilium HelmChartConfig — a few KB, well within a devlxd config value), the same channel identity.nix
# and sops.nix already use. The value is opaque here: the manifests scion decides what rides it.
#
# The chicken-and-egg it closes: cilium is delivered as rke2's own CNI addon (services.rke2.cni, see
# rke2.nix) and customised by the HelmChartConfig this lane seeds — so the CNI comes up WITHOUT Flux,
# the node reaches Ready, then the seeded Flux operator/root take over and reconcile the rest from the
# rendered branch. See docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc.
{ pkgs, ... }:
{
  systemd.services.rke2lab-server-manifests = {
    description = "rke2lab bootstrap server-manifests (devlxd → rke2 server/manifests, before rke2-server)";
    before = [ "rke2-server.service" ];
    wantedBy = [ "rke2-server.service" ];
    after = [ "local-fs.target" ];
    unitConfig.DefaultDependencies = "no";
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [ pkgs.curl ];
    # OPTIONAL by design: a node grown without a bootstrap projection (a bare survey, a peer that
    # joins an existing control plane and needs no bootstrap objects) boots without it — a missing key
    # must not fail the unit, so the fetch is guarded and absence is a clean no-op.
    script = ''
      set -euo pipefail
      sock=/dev/incus/sock
      dir=/var/lib/rancher/rke2/server/manifests
      install -d -m 0700 "$dir"
      if manifests="$(curl -sf --unix-socket "$sock" \
          "http://x/1.0/config/user.rke2lab.server-manifests" 2>/dev/null)"; then
        umask 077
        printf '%s' "$manifests" >"$dir/rke2lab-bootstrap.yaml"
      fi
    '';
  };
}
