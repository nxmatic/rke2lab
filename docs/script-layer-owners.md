# Script Layer Owners

This file is the canonical ownership map for shell script assets in this repository.

## Ownership rules

- Each script has exactly one owner.
- A layer may consume scripts from another owner only through an explicit mounted artifact boundary (for example, a dedicated ConfigMap volume).
- Do not move unrelated scripts under `runtime/containerd-shim-flox`.
- Do not add backward-compatibility aliases for script locations unless explicitly requested.

## Kubernetes manifest-owned scripts

| Script | Owner domain/layer | Owner manifest unit | Source path |
|---|---|---|---|
| `shim-installer.sh` | `runtime/containerd-shim-flox` | `runtime/containerd-shim-flox` | `manifests/src/main/resources/runtime/containerd-shim-flox/shim-installer.sh` |
| `flox-rootfs-sync.sh` | `runtime/containerd-shim-flox` | `runtime/containerd-shim-flox` | `manifests/src/main/resources/runtime/containerd-shim-flox/flox-rootfs-sync.sh` |
| `shim-build.sh` | `runtime/containerd-shim-flox` | `runtime/containerd-shim-flox` | `manifests/src/main/resources/runtime/containerd-shim-flox/shim-build.sh` |
| `shim-build.yaml` | `runtime/containerd-shim-flox` | `runtime/containerd-shim-flox` | `manifests/src/main/resources/runtime/containerd-shim-flox/shim-build.yaml` |
| `install.sh` | `networking/envoy-gateway` | `networking/envoy-gateway` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/networking/EnvoyGatewayLayer.java` (inline ConfigMap data) |
| `kdns-dlv.sh` | `networking/kdns` | `networking/kdns` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/networking/KdnsLayer.java` (inline ConfigMap data) |
| `agent-sync.sh` | `mesh/headplane` | `mesh/headplane` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadplaneLayer.java` (inline ConfigMap data) |
| `config-init.sh` | `mesh/headscale` | `mesh/headscale` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadscaleLayer.java` (inline ConfigMap data) |
| `bootstrap.sh` | `mesh/headscale` | `mesh/headscale` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadscaleLayer.java` (inline ConfigMap data) |
| `tailscale-client.sh` | `mesh/headscale` | `mesh/headscale` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadscaleLayer.java` (inline ConfigMap data) |
| `wait-for-headscale.sh` | `mesh/headscale` | `mesh/headscale` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadscaleLayer.java` (inline ConfigMap data) |
| `gateway.sh` | `mesh/headscale` | `mesh/headscale` | `manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/mesh/HeadscaleLayer.java` (inline ConfigMap data) |

## Host systemd source tree (canonical)

| Source tree | Owner component | Materialized host path | Notes |
|---|---|---|---|
| `host/systemd-scripts` | `manifests` resources (materialized by `controlplane/incus`) | `manifests.d/host/systemd-scripts` | source path `manifests/src/main/resources/host/systemd-scripts`, mounted into node at `/srv/host/systemd-scripts.d` |
| `host/systemd-units` | `manifests` resources (materialized by `controlplane/incus`) | `manifests.d/host/systemd-units` | source path `manifests/src/main/resources/host/systemd-units`, stowed by `rke2lab-systemd-link.sh` from `/srv/host/systemd-units.d` |

## Host bootstrap scripts for Incus nodes

These scripts are owned by the controlplane Incus bootstrap pipeline and are mounted as `/srv/host/systemd-scripts.d` in node instances.

- Owner component: `controlplane/incus`
- Owner code path: `controlplane/src/main/java/io/nxmatic/rk2lab/controlplane/incus/IncusResourceBootstrap.java`
- Owner source tree: `manifests/src/main/resources/host/systemd-scripts/`

Scripts in this owner set:

- `rke2lab-activate.sh`
- `rke2lab-capn-provider-install.sh`
- `rke2lab-cilium-operator-scaling.sh`
- `rke2lab-cilium-ready.sh`
- `rke2lab-cluster-api-install.sh`
- `rke2lab-config-install.sh`
- `rke2lab-configure-containerd-zfs-mount.sh`
- `rke2lab-env-load.sh`
- `rke2lab-flox-install.sh`
- `rke2lab-install-post.sh`
- `rke2lab-install-pre.sh`
- `rke2lab-install.sh`
- `rke2lab-layer-ready.sh`
- `rke2lab-layer-secrets-apply.sh`
- `rke2lab-manifests-install.sh`
- `rke2lab-network-config.sh`
- `rke2lab-network-debug.sh`
- `rke2lab-network-wait.sh`
- `rke2lab-nix-install.sh`
- `rke2lab-openebs-ready.sh`
- `rke2lab-remount-shared.sh`
- `rke2lab-replicator-ready.sh`
- `rke2lab-route-cleanup.sh`
- `rke2lab-server-post-start.sh`
- `rke2lab-server-pre-start.sh`
- `rke2lab-systemd-link.sh`
- `rke2lab-tools-configuration-directories.sh`
- `rke2lab-vip-kubeconfig.sh`

## Notes

- Runtime daemonset policy scripts currently include `daemonset-logging.sh`, `daemonless-trampoline.sh`, and `daemonless-host-asset-materializer.sh` under `manifests/src/main/resources/runtime/daemonset/.sh.d/`.
- `runtime/containerd-shim-flox` is the canonical owner for both installer and build-assets ConfigMaps mounted at `/scripts` and `/build-assets`.
- Shim builder execution is controlled by the canonical daemonless execution contract (`DAEMONLESS_EXEC_MODE=guest|host|pod`).
- `shim-build.sh` accepts `host|guest|pod` through its CLI entrypoints, which in turn set the daemonless execution mode explicitly.
- Default execution mode remains `guest` when omitted.
- `kdns` source resolution is worktree-based (`path:` input), with subtree mode preferred at `networking/kdns/src` and explicit override available via `KDNS_SRC_WORKTREE`.
- Deferred cleanup note: some inline mesh/runtime ConfigMaps (for example `mesh/headplane` Flox env payloads) are candidates to adopt the same deterministic serialized-asset/archive pattern now used by the Flox shim wrapper.
- Next-stage migration guidance is tracked in `docs/rke2lab-authored-notes-import.adoc` under `=== Next-stage migration note`.
- Keep this registry updated whenever a script is added, moved, or deleted.
