# Mesh Packages Build Strategy (@codebase)

## Overview

This document describes the unified approach for building mesh packages (headplane, headscale) from local Nix flakes during Kubernetes cluster initialization.

## Problem Statement

Both `headplane` and `headscale` environments (`nxmatic/headplane`, `nxmatic/headscale`) are not available on the Flox Hub, requiring:
1. Building them locally on the master node
2. Making them available to Kubernetes pods before mesh manifests install
3. Applying consistent patterns across multiple deployments

## Solution Architecture

### Package Sources

**Headplane & Headscale flake**: [rke2.d/bioskop/master/catalog/mesh/headplane/flake.nix](rke2.d/bioskop/master/catalog/mesh/headplane/flake.nix)
- Overlays `github:tale/headplane` which provides both packages
- Builds for `aarch64-linux` and `aarch64-darwin`
- Exports: `headplane`, `headplane-agent`, `headscale`

### Build Flow

```
RKE2 Server Started
    ↓
rke2-headplane-build.service (builds mesh packages)
    ↓
rke2-mesh-manifests-install.service (waits for builds)
    ↓
Kubernetes mesh deployments started with runtimeClassName: flox
```

### System Integration

1. **Mount**: `/var/lib/git` → `/srv/host/git` (in incus config)
   - Provides access to all git working trees
   - Allows flake.nix references by path

2. **Build Service**: `rke2-headplane-build.service`
   - Runs after `rke2-server.service`
   - Executes `rke2-headplane-build.sh` (builds headplane + headscale)
   - Output: `/tmp/mesh-build/{headplane,headplane-agent,headscale}` symlinks

3. **Installation Service Dependency**: `rke2-mesh-manifests-install.service`
   - Requires `rke2-headplane-build.service` to complete
   - Ensures builds available before pods try to pull them

## Flox Annotations

Mesh pods use pod metadata to reference Flox environments:

```yaml
metadata:
  annotations:
    flox.dev/environment: nxmatic/headplane  # or nxmatic/headscale
```

These values are configurable via kpt setters:
- Headplane: [setters.yaml](rke2.d/bioskop/master/catalog/mesh/headplane/setters.yaml) → `headplane-flox-env`
- Headscale: [setters.yaml](rke2.d/bioskop/master/catalog/mesh/headscale/setters.yaml) → `headscale-flox-env`

## Container Runtime Integration

The flox containerd shim:
1. Reads `flox.dev/environment` annotation
2. Attempts to pull environment from Flox Hub (or local registry)
3. Falls back to `runtimeClassName: flox` configured on the node

**Key**: When packages aren't available on Flox Hub, they must be either:
- Built and available in the node's Nix store, OR
- Pushed to a registry accessible from the node

## Steps to Apply Pattern to New Packages

For any new package requiring local building:

1. **Check if flake exists** in catalog directory
   ```bash
   ls -a rke2.d/bioskop/master/catalog/mesh/{package}/flake.nix
   ```

2. **Create systemd build service** (template):
   ```ini
   [Unit]
   Description=Build {package} for aarch64-linux
   After=rke2-server.service
   Requires=rke2-server.service
   ConditionPathExists=/srv/host/git/nxmatic/rke2lab/rke2.d/bioskop/master/catalog/mesh/{package}/flake.nix
   
   [Service]
   Type=oneshot
   ExecStart=/srv/host/scripts.d/rke2-{package}-build.sh
   RemainAfterExit=true
   Environment="PATH=/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin"
   
   [Install]
   WantedBy=multi-user.target
   ```

3. **Create build script** that:
   - Validates flake.nix exists
   - Runs `nix build .#packages.aarch64-linux.{package-name}`
   - Outputs to appropriate directory
   - Logs to `/var/log/rke2-{package}-build.log`

4. **Update manifest install service** to depend on build service:
   ```ini
   After=rke2-{package}-build.service
   Requires=rke2-{package}-build.service
   ```

5. **Alternative: Push to registry** (if not building locally)
   - Use `rke2-{package}-push.service` for registry pushes
   - Update manifest install to wait for push to complete
   - Update pod annotations to reference registry image

## Troubleshooting

### Check build logs
```bash
ssh bioskop-nixos.local -- incus exec master -- journalctl -u rke2-headplane-build.service -f
```

### Verify build outputs
```bash
ssh bioskop-nixos.local -- incus exec master -- ls -la /tmp/mesh-build/
```

### Check flox environment resolution
```bash
ssh bioskop-nixos.local -- incus exec master -- flox list nxmatic/headplane
```

### Monitor Flox shim logs
```bash
ssh bioskop-nixos.local -- incus exec master -- tail -f /var/lib/rancher/rke2/agent/containerd/containerd.log | grep flox
```

## Future Considerations

1. **Cachix integration**: Push built packages to `nxmatic.cachix.org` for faster builds across clusters
2. **Build matrix**: Extend builds for multi-cluster (alcide, etc.)
3. **Container registry**: Host headplane/headscale OCI images in local registry instead of Flox environments
4. **Cross-compilation**: Build for multiple architectures if supporting mixed-arch clusters
