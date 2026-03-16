# Script Layer Owners

This file is the canonical ownership map for shell script assets in this repository.

## Ownership rules

- Each script has exactly one owner.
- A layer may consume scripts from another owner only through an explicit mounted artifact boundary (for example, a dedicated ConfigMap volume).
- Do not move unrelated scripts under `runtime/flox-containerd-shim`.
- Do not add backward-compatibility aliases for script locations unless explicitly requested.

## Kubernetes manifest-owned scripts

| Script | Owner domain/layer | Owner manifest unit | Source path |
|---|---|---|---|
| `shim-installer.sh` | `runtime/flox-containerd-shim` | `runtime/flox-containerd-shim` | `manifests/src/main/resources/runtime/flox-containerd-shim/shim-installer.sh` |
| `rke2lab-flox-build.sh` | `runtime/flox-container-build-assets` | `runtime/flox-container-build-assets` | `manifests/src/main/resources/runtime/flox-container-build-assets/rke2lab-flox-build.sh` |
| `rke2lab-flox-build.yaml` | `runtime/flox-container-build-assets` | `runtime/flox-container-build-assets` | `manifests/src/main/resources/runtime/flox-container-build-assets/rke2lab-flox-build.yaml` |
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
| `host/systemd-scripts` | `manifests` resources (materialized by `controlplane/incus`) | `manifests.d/host/systemd-scripts` | source path `manifests/src/main/resources/host/systemd-scripts`, mounted into node at `/srv/host/scripts.d` |
| `host/systemd-units` | `manifests` resources (materialized by `controlplane/incus`) | `manifests.d/host/systemd-units` | source path `manifests/src/main/resources/host/systemd-units`, stowed by `rke2lab-systemd-link.sh` from `/srv/host/system.d` |

## Host bootstrap scripts for Incus nodes

These scripts are owned by the controlplane Incus bootstrap pipeline and are mounted as `/srv/host/scripts.d` in node instances.

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
- `rke2lab-flox-build.sh`
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
- `rke2lab-nix-build.sh`
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

- `runtime/flox-containerd-shim` now consumes flox build assets from the dedicated `flox-container-build-assets` owner via a separate ConfigMap mount (`/build-assets`).
- Keep this registry updated whenever a script is added, moved, or deleted.
