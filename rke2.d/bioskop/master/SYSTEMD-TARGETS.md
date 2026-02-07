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
    ├─ rke2-server-ready.target
    │   └─ After: rke2-server.service
    │
    ├─ rke2-builds-complete.target
    │   └─ After: rke2-build-packages.service
    │
    ├─ rke2-runtime-ready.target  
    │   └─ After: rke2-runtime-ready-check.service
    │
    ├─ rke2-networking-ready.target
    │   └─ After: rke2-networking-ready-check.service
    │
    ├─ rke2-storage-ready.target
    │   └─ After: rke2-storage-ready-check.service
    │
    ├─ rke2-replication-ready.target
    │   └─ After: rke2-replication-ready-check.service
    │
    └─ rke2-mesh-ready.target
        └─ After: rke2-mesh-ready-check.service
```

## Linear Boot Stages

Each target represents a **milestone** in the cluster initialization:

### Stage 1: Server Ready
**Target**: `rke2-server-ready.target`
- RKE2 server process running
- Kubernetes API server available
- Node registered

**Required by**: `rke2-builds-complete.target`

---

### Stage 2: Builds Complete
**Target**: `rke2-builds-complete.target`
- All Nix packages built from `nix-builds.yaml`
- Outputs available in `/tmp/*-build/`
- Flox environments available to containerd

**Services that finish before this target**:
- `rke2-build-packages.service`

**Required by**: `rke2-runtime-ready.target`

**Why this stage exists:**
- Ensures packages are available before any pods try to use them
- Blocks deployment if builds fail
- All downstream layers implicitly guarantee packages are ready

---

### Stage 3: Runtime Ready
**Target**: `rke2-runtime-ready.target`
- Kubernetes runtime components deployed (CNI plugins, etc.)
- Core system pods running
- Cluster can schedule workloads

**Depends on**: `rke2-builds-complete.target`
**Services that finish before this target**:
- `rke2-runtime-ready-check.service`

**Required by**: `rke2-networking-ready.target`

---

### Stage 4: Networking Ready
**Target**: `rke2-networking-ready.target`
- Cilium CNI deployed and healthy
- Network policies active
- Pod networking functional

**Depends on**: `rke2-runtime-ready.target`
**Services that finish before this target**:
- `rke2-networking-ready-check.service`

**Required by**: `rke2-storage-ready.target`

---

### Stage 5: Storage Ready
**Target**: `rke2-storage-ready.target`
- OpenEBS ZFS CSI driver deployed
- Storage classes available
- PVCs can be provisioned

**Depends on**: `rke2-networking-ready.target`
**Services that finish before this target**:
- `rke2-storage-ready-check.service`

**Required by**: `rke2-replication-ready.target`

---

### Stage 6: Replication Ready
**Target**: `rke2-replication-ready.target`
- Longhorn or other replication systems deployed
- HA storage available

**Depends on**: `rke2-storage-ready.target`
**Services that finish before this target**:
- `rke2-replication-ready-check.service`

**Required by**: `rke2-mesh-ready.target`

---

### Stage 7: Mesh Ready
**Target**: `rke2-mesh-ready.target`
- Headscale/Headplane deployed
- VPN mesh networking active
- Cluster fully operational

**Depends on**: `rke2-replication-ready.target`
**Services that finish before this target**:
- `rke2-mesh-ready-check.service`

**Final stage**: Cluster is production-ready

---

## Service Dependency Patterns

### ✅ NEW: Depend on Targets (Preferred)

```ini
[Unit]
Description=My Custom Service
After=rke2-builds-complete.target
Requires=rke2-builds-complete.target
Before=rke2-mesh-ready.target

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
After=rke2-runtime-ready-check.service
Requires=rke2-runtime-ready-check.service
After=rke2-networking-ready-check.service
Requires=rke2-networking-ready-check.service
After=rke2-storage-ready-check.service
Requires=rke2-storage-ready-check.service
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
rke2-install.service
    ↓
rke2-server.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-server-ready.target                    │
└─────────────────────────────────────────────┘
    ↓
rke2-build-packages.service (builds all Nix packages)
    ↓
┌─────────────────────────────────────────────┐
│ rke2-builds-complete.target                 │  ← Guarantees packages available
└─────────────────────────────────────────────┘
    ↓
rke2-runtime-secrets.service
rke2-runtime-manifests-install.service
rke2-runtime-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-runtime-ready.target                   │
└─────────────────────────────────────────────┘
    ↓
rke2-networking-secrets.service
rke2-networking-manifests-install.service
rke2-networking-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-networking-ready.target                │
└─────────────────────────────────────────────┘
    ↓
rke2-storage-secrets.service
rke2-storage-manifests-install.service
rke2-storage-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-storage-ready.target                   │
└─────────────────────────────────────────────┘
    ↓
rke2-replication-manifests-install.service
rke2-replication-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-replication-ready.target               │
└─────────────────────────────────────────────┘
    ↓
rke2-mesh-secrets.service
rke2-mesh-manifests-install.service
rke2-mesh-ready-check.service
    ↓
┌─────────────────────────────────────────────┐
│ rke2-mesh-ready.target                      │
└─────────────────────────────────────────────┘
    ↓
multi-user.target (system fully operational)
```

---

## Adding Services to Stages

### Add a service to existing stage

**Before Runtime Ready:**
```ini
[Unit]
Description=My Runtime Service
After=rke2-builds-complete.target
Requires=rke2-builds-complete.target
Before=rke2-runtime-ready.target
```

**Before Mesh Ready:**
```ini
[Unit]
Description=My Mesh Service  
After=rke2-replication-ready.target
Requires=rke2-replication-ready.target
Before=rke2-mesh-ready.target
```

### Create a new stage (advanced)

If you need a custom layer between existing stages:

```ini
# make.d/incus/systemd/rke2-mycustom-ready.target
[Unit]
Description=RKE2 My Custom Layer Ready Target
After=rke2-storage-ready.target
Requires=rke2-storage-ready.target
After=rke2-mycustom-ready-check.service
Requires=rke2-mycustom-ready-check.service

[Install]
WantedBy=multi-user.target
```

Then update replication target to depend on your new target:
```ini
# rke2-replication-ready.target
After=rke2-mycustom-ready.target
Requires=rke2-mycustom-ready.target
```

---

## Debugging with Targets

### Check which targets are active
```bash
ssh bioskop-nixos.local -- incus exec master -- \
  systemctl list-units --type=target | grep rke2
```

### Wait for a specific stage
```bash
ssh bioskop-nixos.local -- incus exec master -- \
  systemctl is-active rke2-builds-complete.target
```

### See what's blocking a target
```bash
ssh bioskop-nixos.local -- incus exec master -- \
  systemctl list-dependencies --reverse rke2-builds-complete.target
```

### Monitor boot sequence in real-time
```bash
ssh bioskop-nixos.local -- incus exec master -- \
  journalctl -f -u 'rke2-*.target'
```

---

## Migration from Service Dependencies

### Before (direct dependencies)
```ini
[Unit]
After=rke2-runtime-ready-check.service
Requires=rke2-runtime-ready-check.service
After=rke2-networking-ready-check.service
Requires=rke2-networking-ready-check.service
After=rke2-storage-ready-check.service
Requires=rke2-storage-ready-check.service
After=rke2-build-packages.service
Requires=rke2-build-packages.service
```

### After (target-based)
```ini
[Unit]
After=rke2-storage-ready.target
Requires=rke2-storage-ready.target
```

**Automatically includes**:
- ✅ Builds complete (via rke2-builds-complete.target)
- ✅ Runtime ready (via rke2-runtime-ready.target)
- ✅ Networking ready (via rke2-networking-ready.target)
- ✅ Storage ready (via rke2-storage-ready.target)

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
   - Targets: `rke2-{layer}-ready.target`
   - Check services: `rke2-{layer}-ready-check.service`
   - Install services: `rke2-{layer}-manifests-install.service`

---

## Related Files

- Target definitions: [make.d/incus/systemd/rke2-*-ready.target](make.d/incus/systemd/)
- Build service: [make.d/incus/systemd/rke2-build-packages.service](make.d/incus/systemd/rke2-build-packages.service)
- Mesh install service: [make.d/incus/systemd/rke2-mesh-manifests-install.service](make.d/incus/systemd/rke2-mesh-manifests-install.service)
- Build descriptor: [rke2.d/bioskop/master/nix-builds.yaml](rke2.d/bioskop/master/nix-builds.yaml)
