# RKE2 Systemd Target Architecture (@codebase)

## Overview

This document describes the **target-based staging system** for RKE2 initialization that:
- Uses systemd targets to represent deployment stages
- Simplifies service dependencies (depend on targets, not individual services)
- Makes the boot sequence explicit and linear
- Follows systemd best practices (like `network.target`, `multi-user.target`)

## Target Hierarchy

```
multi-user.target
    ↑
    ├─ rke2lab-server-ready.target
    │   └─ After: rke2-server.service
    │
    ├─ rke2lab-flox-nix-builds-complete.target
    │   └─ After: rke2lab-flox-nix-build.service
    │
    ├─ rke2lab-runtime-ready.target  
    │   └─ After: rke2lab-runtime-ready-check.service
    │
    ├─ rke2lab-networking-ready.target
    │   └─ After: rke2lab-networking-ready-check.service
    │
    ├─ rke2lab-storage-ready.target
    │   └─ After: rke2lab-storage-ready-check.service
    │
    ├─ rke2lab-replication-ready.target
    │   └─ After: rke2lab-replication-ready-check.service
    │
    └─ rke2lab-mesh-ready.target
        └─ After: rke2lab-mesh-ready-check.service
```

## Linear Boot Stages

Each target represents a **milestone** in the cluster initialization:

### Stage 1: Server Ready
**Target**: `rke2lab-server-ready.target`
- RKE2 server process running
- Kubernetes API server available
- Node registered

**Notes**: Flox Nix builds now run independently and no longer gate this target.

---

### Stage 2: Flox Nix Builds Complete
**Target**: `rke2lab-flox-nix-builds-complete.target`
- All Nix packages built from `nix-builds.yaml`
- Outputs available in `/tmp/*-build/`
- Flox environments available to containerd

**Services that finish before this target**:
- `rke2lab-flox-nix-build.service`

**Required by**: _None (runs concurrently)_

**Why this stage exists:**
- Ensures packages are available before any pods try to use them
- Runs in parallel with other stages

---

### Stage 3: Runtime Ready
**Target**: `rke2lab-runtime-ready.target`
- Kubernetes runtime components deployed (CNI plugins, etc.)
- Core system pods running
- Cluster can schedule workloads

**Services that finish before this target**:
- `rke2lab-runtime-ready-check.service`

**Required by**: `rke2lab-networking-ready.target`

---

### Stage 4: Networking Ready
**Target**: `rke2lab-networking-ready.target`
- Cilium CNI deployed and healthy
- Network policies active
- Pod networking functional

**Depends on**: `rke2lab-runtime-ready.target`
**Services that finish before this target**:
- `rke2lab-networking-ready-check.service`

**Required by**: `rke2lab-storage-ready.target`

---

### Stage 5: Storage Ready
**Target**: `rke2lab-storage-ready.target`
- OpenEBS ZFS CSI driver deployed
- Storage classes available
- PVCs can be provisioned

**Depends on**: `rke2lab-networking-ready.target`
**Services that finish before this target**:
- `rke2lab-storage-ready-check.service`

**Required by**: `rke2lab-replication-ready.target`

---

### Stage 6: Replication Ready
**Target**: `rke2lab-replication-ready.target`
- Longhorn or other replication systems deployed
- HA storage available

**Depends on**: `rke2lab-storage-ready.target`
**Services that finish before this target**:
- `rke2lab-replication-ready-check.service`

**Required by**: `rke2lab-mesh-ready.target`

---

### Stage 7: Mesh Ready
**Target**: `rke2lab-mesh-ready.target`
- Headscale/Headplane deployed
- VPN mesh networking active
- Cluster fully operational

**Depends on**: `rke2lab-replication-ready.target`
**Services that finish before this target**:
- `rke2lab-mesh-ready-check.service`

**Final stage**: Cluster is production-ready

---

## Service Dependency Patterns

### ✅ NEW: Depend on Targets (Preferred)

```ini
[Unit]
Description=My Custom Service
After=rke2lab-flox-nix-builds-complete.target
Requires=rke2lab-flox-nix-builds-complete.target
Before=rke2lab-mesh-ready.target

[Service]
...
```

**Benefits:**
- Clear stage boundary
- Don't care about specific services in earlier stages
- Easy to add services to any stage without changing dependencies
- Self-documenting (stage names make purpose obvious)

### ❌ OLD: Direct Service Dependencies

```ini
[Unit]
Description=My Custom Service
After=rke2lab-runtime-ready-check.service
Requires=rke2lab-runtime-ready-check.service
After=rke2lab-networking-ready-check.service
Requires=rke2lab-networking-ready-check.service
After=rke2lab-storage-ready-check.service
Requires=rke2lab-storage-ready-check.service
# ... many dependencies ...
```

**Problems:**
- Hard to maintain (long dependency chains)
- Fragile (breaks if service names change)
- Unclear (which stage does this belong to?)

---

## Complete Boot Flow with Targets

```
systemd boot
    ↓
local-fs.target (mount filesystems)
    ↓
rke2lab-install.service
    ↓
rke2-server.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-server-ready.target                 │
└─────────────────────────────────────────────┘
    ↓
rke2lab-runtime-secrets.service
rke2lab-runtime-manifests-install.service
rke2lab-runtime-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-runtime-ready.target                │
└─────────────────────────────────────────────┘
    ↓
rke2lab-networking-secrets.service
rke2lab-networking-manifests-install.service
rke2lab-networking-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-networking-ready.target             │
└─────────────────────────────────────────────┘
    ↓
rke2lab-storage-secrets.service
rke2lab-storage-manifests-install.service
rke2lab-storage-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-storage-ready.target                │
└─────────────────────────────────────────────┘
    ↓
rke2lab-replication-manifests-install.service
rke2lab-replication-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-replication-ready.target            │
└─────────────────────────────────────────────┘
    ↓
rke2lab-mesh-secrets.service
rke2lab-mesh-manifests-install.service
rke2lab-mesh-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-mesh-ready.target                   │
└─────────────────────────────────────────────┘
    ↓
multi-user.target (system fully operational)

In parallel (non-blocking):
local-fs.target
    ↓
rke2lab-flox-nix-build.service (builds all Nix packages)
    ↓
┌─────────────────────────────────────────────┐
│ rke2lab-flox-nix-builds-complete.target     │  ← Guarantees packages available
└─────────────────────────────────────────────┘
```

---

## Adding Services to Stages

### Add a service to existing stage

**Before Runtime Ready:**
```ini
[Unit]
Description=My Runtime Service
After=rke2lab-flox-nix-builds-complete.target
Requires=rke2lab-flox-nix-builds-complete.target
Before=rke2lab-runtime-ready.target
```

**Before Mesh Ready:**
```ini
[Unit]
Description=My Mesh Service  
After=rke2lab-replication-ready.target
Requires=rke2lab-replication-ready.target
Before=rke2lab-mesh-ready.target
```

### Create a new stage (advanced)

If you need a custom layer between existing stages:

```ini
# make.d/incus/systemd/rke2lab-mycustom-ready.target
[Unit]
Description=RKE2 My Custom Layer Ready Target
After=rke2lab-storage-ready.target
Requires=rke2lab-storage-ready.target
After=rke2lab-mycustom-ready-check.service
Requires=rke2lab-mycustom-ready-check.service

[Install]
WantedBy=multi-user.target
```

Then update replication target to depend on your new target:
```ini
# rke2lab-replication-ready.target
After=rke2lab-mycustom-ready.target
Requires=rke2lab-mycustom-ready.target
```

---

## Debugging with Targets

### Check which targets are active
```bash
ssh bioskop-nixos.local -- incus exec master -- \
    systemctl list-units --type=target | grep rke2lab
```

### Wait for a specific stage
```bash
ssh bioskop-nixos.local -- incus exec master -- \
    systemctl is-active rke2lab-flox-nix-builds-complete.target
```

### See what's blocking a target
```bash
ssh bioskop-nixos.local -- incus exec master -- \
    systemctl list-dependencies --reverse rke2lab-flox-nix-builds-complete.target
```

### Monitor boot sequence in real-time
```bash
ssh bioskop-nixos.local -- incus exec master -- \
    journalctl -f -u 'rke2lab-*.target'
```

---

## Migration from Service Dependencies

### Before (direct dependencies)
```ini
[Unit]
After=rke2lab-runtime-ready-check.service
Requires=rke2lab-runtime-ready-check.service
After=rke2lab-networking-ready-check.service
Requires=rke2lab-networking-ready-check.service
After=rke2lab-storage-ready-check.service
Requires=rke2lab-storage-ready-check.service
After=rke2lab-flox-nix-build.service
Requires=rke2lab-flox-nix-build.service
```

### After (target-based)
```ini
[Unit]
After=rke2lab-storage-ready.target
Requires=rke2lab-storage-ready.target
```

**Automatically includes**:
- ✅ Runtime ready (via rke2lab-runtime-ready.target)
- ✅ Networking ready (via rke2lab-networking-ready.target)
- ✅ Storage ready (via rke2lab-storage-ready.target)

Optional:
- ✅ Flox builds complete (via rke2lab-flox-nix-builds-complete.target)

All because targets form a dependency chain!

---

## Best Practices

1. **Always use targets for cross-layer dependencies**
   - Don't depend on services in other layers
   - Depend on the target representing that layer

2. **Services belong to ONE stage**
   - Use `Before:` to declare which target you contribute to
   - Use `After:` to depend on previous stage targets

3. **Keep targets lightweight**
   - Targets don't run code
   - They're just synchronization points

4. **Document in service descriptions**
   ```ini
   Description=Install mesh manifests (stage: mesh)
   ```

5. **Use naming convention**
- Targets: `rke2lab-{layer}-ready.target`
- Check services: `rke2lab-{layer}-ready-check.service`
- Install services: `rke2lab-{layer}-manifests-install.service`

---

## Related Files

- Target definitions: [make.d/incus/systemd/rke2lab-*-ready.target](make.d/incus/systemd/)
- Build service: [make.d/incus/systemd/rke2lab-flox-nix-build.service](make.d/incus/systemd/rke2lab-flox-nix-build.service)
- Mesh install service: [make.d/incus/systemd/rke2lab-mesh-manifests-install.service](make.d/incus/systemd/rke2lab-mesh-manifests-install.service)
- Build descriptor: [rke2.d/bioskop/master/nix-builds.yaml](rke2.d/bioskop/master/nix-builds.yaml)
