# Generalized RKE2 Package Build System (@codebase)

## Overview

This document describes the **descriptor-driven** build system for RKE2 that:
- Defines build jobs in a single Nix build descriptor file (`nix-builds.yaml`)
- Uses a generic systemd service and build script
- Eliminates the need for package-specific services
- Scales easily to multiple packages and clusters

## Architecture

### Files

1. **Descriptor**: [rke2.d/bioskop/master/nix-builds.yaml](rke2.d/bioskop/master/nix-builds.yaml)
   - Defines all Nix build jobs
   - YAML format with version control
   - Mounted in Incus container at `/srv/host/nix-builds.yaml`

2. **Generic Build Script**: [make.d/incus/scripts/rke2-nix-build.sh](make.d/incus/scripts/rke2-nix-build.sh)
   - Parses the descriptor with `yq`
   - Iterates through all enabled jobs
   - Builds packages and logs results
   - Input: descriptor file path (default: `/srv/host/nix-builds.yaml`)

3. **Generic Systemd Service**: [make.d/incus/systemd/rke2lab-flox-nix-build.service](make.d/incus/systemd/rke2lab-flox-nix-build.service)
  - Runs the generic build script
  - Executes early (after local filesystems)
  - Runs in parallel with other stages

4. **Incus Mount**: `nix-builds.descriptor` in instance config
   - Binds descriptor file to `/srv/host/nix-builds.yaml`
   - Allows single source of truth

## Descriptor Format

```yaml
version: "1"

jobs:
  - name: mesh                          # Job identifier
    description: "..."                  # Human-readable description
    enabled: true                       # Enable/disable without removing
    flakePath: /srv/host/git/.../      # Absolute path to flake directory
    outputDir: /tmp/mesh-build         # Where to symlink built packages
    logFile: /var/log/rke2-mesh-...    # Build log location
    packages:
      - name: headplane                # Package name (for symlink)
        attr: "packages.aarch64-linux.headplane"  # Nix attribute path
      - name: headplane-agent
        attr: "packages.aarch64-linux.headplane-agent"
```

## Adding New Build Jobs

### Step 1: Create/verify flake in catalog
```bash
ls rke2.d/bioskop/master/catalog/{component}/flake.nix
```

### Step 2: Add job to descriptor
Edit [nix-builds.yaml](rke2.d/bioskop/master/nix-builds.yaml):

```yaml
jobs:
  # ... existing jobs ...
  - name: mycomponent
    description: "Build mycomponent from custom flake"
    enabled: true
    flakePath: /srv/host/git/nxmatic/rke2lab/rke2.d/bioskop/master/catalog/mycomponent
    outputDir: /tmp/mycomponent-build
    logFile: /var/log/rke2-mycomponent-build.log
    packages:
      - name: mycomponent
        attr: "packages.aarch64-linux.mycomponent"
```

### Step 3: Update manifest install service (if needed)
Only if you have a manifest install service that depends on this package:

```ini
[Unit]
...
After=rke2lab-flox-nix-builds-complete.target
Requires=rke2lab-flox-nix-builds-complete.target
```

### Step 4: Test
- Restart the master node
- Check logs: `journalctl -u rke2lab-flox-nix-build.service -f`
- Verify outputs: `ls -la /tmp/mycomponent-build/`

## Service Execution Flow

```
RKE2 Server Started
  ↓
rke2lab-mesh-manifests-install.service (and others)
  ↓
Kubernetes pods start with flox.dev/environment annotations

In parallel (non-blocking):
local-fs.target
  ↓
rke2lab-flox-nix-build.service
    │
    ├─ Read /srv/host/nix-builds.yaml
    │
    ├─ For each enabled job:
    │  ├─ Verify flake.nix exists
    │  ├─ For each package in job:
    │  │  └─ nix build .#packages.aarch64-linux.{package}
    │  └─ Create symlinks in outputDir
    │
    └─ Log results to journal + per-job log file
    ↓
Flox shim resolves environment to packages in /tmp/*-build/
```

## Key Features

### 1. Centralized Configuration
- Single YAML file for all builds
- No need to create new systemd services
- Easy to review all build jobs at once

### 2. Dynamic Job Discovery
- Script uses `yq` to parse jobs
- No hardcoding of job names
- Jobs can be disabled/enabled without deletion

### 3. Detailed Logging
- Per-job log files
- Journal entries for systemd integration
- Build summaries with success/failure counts

### 4. Error Handling
- Continues building other packages if one fails
- Reports failed jobs at end
- Non-zero exit if any job fails

### 5. Extensibility
- One descriptor format for all clusters (bioskop, alcide, etc.)
- Can override `/srv/host/builds.yaml` per cluster
- Script is system-agnostic (works with any flake)

## Troubleshooting

### Check descriptor is mounted
```bash
ssh bioskop-nixos.local -- incus exec master -- cat /srv/host/nix-builds.yaml
```

### Watch build in progress
```bash
ssh bioskop-nixos.local -- incus exec master -- journalctl -u rke2lab-flox-nix-build.service -f
```

### Check build outputs
```bash
ssh bioskop-nixos.local -- incus exec master -- ls -la /tmp/*-build/
```

### Check per-job logs
```bash
ssh bioskop-nixos.local -- incus exec master -- cat /var/log/rke2-mesh-build.log
```

### Verify descriptor parsing
```bash
ssh bioskop-nixos.local -- incus exec master -- yq eval '.jobs[0]' /srv/host/nix-builds.yaml
```

## Multi-Cluster Support

For multiple clusters (bioskop, alcide, etc.):

### Option A: Shared descriptor (preferred)
- Single `nix-builds.yaml` in rke2.d catalog root
- All clusters use the same build jobs
- Mount at `/srv/host/nix-builds.yaml` on each cluster

### Option B: Cluster-specific descriptors
- `rke2.d/bioskop/master/nix-builds.yaml`
- `rke2.d/alcide/master/nix-builds.yaml`
- Mount each cluster's descriptor separately

### Option C: Build aggregation
- Build packages once on central node
- Push to cachix.org or OCI registry
- All clusters pull from cache instead of building

## Future Enhancements

1. **Build matrix for multiple architectures**
   ```yaml
   packages:
     - name: headplane
       attr: "packages.{arch}.headplane"
       architectures: [aarch64-linux, x86_64-linux]
   ```

2. **Conditional builds based on node capabilities**
   ```yaml
   jobs:
     - name: gpu-toolkit
       enabled: ${HAS_GPU}
   ```

3. **Build caching and distribution**
   ```yaml
   cachix:
     enable: true
     cache: nxmatic
   ```

4. **Build dependency ordering**
   ```yaml
   jobs:
     - name: mycomponent
       dependsOn: [otherjob]
   ```

5. **Metrics and observability**
   ```yaml
   monitoring:
     prometheus: true
     logLevel: debug
   ```

## Backward Compatibility

The legacy headplane build service (if still present on older nodes) is **not used** by mesh manifests. Consider:
- Keeping it as a fallback (don't break it)
- Or remove it once generic system is validated
- Update documentation to point to generic builder

## Related Files

- [rke2.d/bioskop/master/nix-builds.yaml](rke2.d/bioskop/master/nix-builds.yaml) - Nix build descriptor
- [make.d/incus/scripts/rke2-nix-build.sh](make.d/incus/scripts/rke2-nix-build.sh) - Generic build script
- [make.d/incus/systemd/rke2lab-flox-nix-build.service](make.d/incus/systemd/rke2lab-flox-nix-build.service) - Systemd service
- [rke2.d/bioskop/master/incus-instance-config.yaml](rke2.d/bioskop/master/incus-instance-config.yaml) - Incus mount config
- [MESH-BUILD-STRATEGY.md](rke2.d/bioskop/master/catalog/mesh/MESH-BUILD-STRATEGY.md) - Original strategy document
- [FLOX-INVENTORY.md](rke2.d/bioskop/master/catalog/mesh/FLOX-INVENTORY.md) - Flox environment usage
