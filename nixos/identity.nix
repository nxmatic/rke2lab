# rke2lab per-node identity — resolved at boot from the incus instance's user.rke2lab.node-* config
# keys over devlxd (/dev/incus/sock, always present in an incus guest). The scion projected these from
# the netplan blueprint (GrowIdentityView); this oneshot reads them back and (a) writes the shared
# /run/rke2lab/node.env the zfs mount consumes, (b) sets the transient hostname to <cluster>-<node> so
# mDNS resolves it and rke2 registers the node under it. Ordered before rke2 and avahi so both see the
# resolved hostname. No cloud-init, no host file mount — six scalars over the guest API: the four
# per-node identity facts plus the two PER-CLUSTER dual-stack CIDRs (pod/service) rke2's cluster-cidr
# is baked from at boot (the homogeneous image can't hardcode them — they differ per cluster).
#
# The sops material (age key + cluster-CA bundle) is a SEPARATE devlxd fetch owned by the sops concern
# — see ./sops.nix (rke2lab-sops-fetch), which must run in the early sysinit phase before
# sops-install-secrets, whereas identity is ordered around the multi-user services that read its output.
{ pkgs, ... }:
{
  systemd.services.rke2lab-identity = {
    description = "rke2lab node identity (devlxd → /run/rke2lab/node.env + hostname)";
    wantedBy = [ "multi-user.target" ];
    before = [
      "rke2-server.service"
      "rke2lab-zfs-containerd.service"
      "avahi-daemon.service"
    ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [ pkgs.curl ];
    script = ''
      set -euo pipefail
      sock=/dev/incus/sock
      get() {
        # devlxd exposes user.* keys at /1.0/config/<key>; the socket is up from container start,
        # but retry briefly to be robust against an early first read.
        for _ in $(seq 1 20); do
          if curl -sf --unix-socket "$sock" "http://x/1.0/config/user.rke2lab.$1"; then
            return 0
          fi
          sleep 0.5
        done
        echo "rke2lab-identity: devlxd key user.rke2lab.$1 unavailable" >&2
        return 1
      }
      name="$(get node-name)"
      hostname="$(get node-hostname)"
      kind="$(get node-kind)"
      id="$(get node-id)"
      podcidr="$(get cluster-pod-cidr)"
      servicecidr="$(get cluster-service-cidr)"
      install -d -m 0755 /run/rke2lab
      umask 022
      cat >/run/rke2lab/node.env <<EOF
      RKE2LAB_NODE_NAME=$name
      RKE2LAB_NODE_HOSTNAME=$hostname
      RKE2LAB_NODE_KIND=$kind
      RKE2LAB_NODE_ID=$id
      RKE2LAB_CLUSTER_POD_CIDR=$podcidr
      RKE2LAB_CLUSTER_SERVICE_CIDR=$servicecidr
      EOF
      # Transient hostname (no dbus/hostnamed dependency at this ordering point) — avahi and rke2,
      # ordered after, read it via gethostname().
      printf '%s' "$hostname" >/proc/sys/kernel/hostname
    '';
  };
}
