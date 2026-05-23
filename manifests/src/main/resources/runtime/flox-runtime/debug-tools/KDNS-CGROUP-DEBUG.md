# kdns CrashLoopBackOff Diagnostic

> **OBSOLETE**: This document describes issues with the old containerd-shim-flox approach.
> The cgroup path issue was **fixed by migrating to NRI (Node Resource Interface) plugin**.
> NRI plugins do not control cgroup paths - containerd handles this correctly.
>
> Kept for historical reference only.

---

**Date**: 2026-05-23  
**Issue**: kdns containers start but immediately crash with exit 255  
**Tool**: `crictl-kdns-repro.sh`  
**Status**: Fixed by NRI migration

## Problem Summary

kdns pods were experiencing CrashLoopBackOff with no container logs. Using `crictl-kdns-repro.sh` to reproduce the issue directly with crictl revealed the root cause.

## Error Chain

```
DeadlineExceeded (timeout)
   ↓ Fixed: removed flox.dev/debug-suspend annotation
Flake not supported error  
   ↓ Fixed: switched from flake reference to store-path in manifest
Containers starting but crashing immediately (exit 255, no logs)
   ↓ Diagnosed with crictl-kdns-repro.sh
**→ Cgroup path format mismatch** ← CURRENT ISSUE
```

## Root Cause

The `containerd-shim-flox-2x` shim passes an incorrect cgroup path format to runc when `SystemdCgroup = true`:

```
Error: OCI runtime create failed: runc create failed: 
expected cgroupsPath to be of format "slice:prefix:name" for systemd cgroups, 
got "/k8s.io/ecfc60fba3c3a3481b7cc930da651e6ba3a8635db52a150e8ded054859ec503b" instead
```

### Details

1. **Containerd config**: `SystemdCgroup = true` is set for all runtimes (runc, flox, flox-delve)
2. **Expected format**: `kubepods.slice:kubepods:pod<uid>:container<cid>` (systemd)
3. **Actual format**: `/k8s.io/<sandbox-id>` (cgroupfs)
4. **Where**: The bug is in `containerd-shim-flox-2x` (external package from flox runtime)
5. **Scope**: Affects both `flox` and `flox-delve` runtime classes

### Why Hidden Until Now

Each problem masked the next:
1. **Debug-suspend blocking**: Shim wrapper paused, preventing containers from starting → DeadlineExceeded
2. **Flake not supported**: Shim rejected flake references in manifest → Failed to create task
3. **Cgroup format**: Only visible once containers actually attempt to start

## How Diagnosed

Used `crictl-kdns-repro.sh` which:
1. Captured config from existing kdns pod
2. Generated minimal reproduction configs
3. Used `crictl runp --runtime flox` to bypass Kubernetes layers
4. Called containerd CRI API directly
5. Captured raw runc error output

### Key Script Features

- **Direct CRI access**: Talks to containerd, not kubectl/kubelet
- **Config cloning**: Copies exact annotations/cgroups from real pod
- **Isolation**: Creates test objects with unique names
- **Error capture**: Preserves failed sandbox/container for inspection
- **Runtime forcing**: `--runtime flox` triggers the shim

## Verification

```bash
# Check container actually started (not "Failed to create")
kubectl describe pod -n rke2lab-system <kdns-pod> | grep Started:
# Output: Started: <timestamp>  ← Confirms containers were created and started

# Check exit code
kubectl get pod -n rke2lab-system <kdns-pod> -o yaml | grep exitCode
# Output: exitCode: 255  ← Generic error (crashed immediately)

# Reproduce with crictl
/srv/host/rke2lab-share.d/crictl-kdns-repro.sh
# Output: Shows full runc cgroup error
```

## Possible Solutions

### Option 1: Fix containerd-shim-flox-2x (upstream)
- Proper fix but requires finding maintainer
- Add cgroup path conversion: cgroupfs → systemd format
- Time: Long (external dependency)

### Option 2: Patch in wrapper (our code) ← **CHOSEN SOLUTION**
- Add `fixCgroupPath()` before exec'ing real shim
- Read bundle config.json
- Convert cgroupsPath to systemd format
- Write back before exec
- Time: Medium (requires Go code + rebuild)
- **Why**: Kubernetes strongly recommends systemd cgroup driver on systemd-based systems. Using cgroupfs causes two competing cgroup managers and system instability under resource pressure.

### Option 3: Disable systemd cgroups (workaround) ← **REJECTED**
- Change containerd config: `SystemdCgroup = false`
- Change kubelet config: `cgroupDriver: cgroupfs`
- Pros: Quick, no code changes
- Cons: **Not recommended** for production systemd systems per Kubernetes docs
- **Kubernetes warning**: "nodes that are configured to use cgroupfs for the kubelet and container runtime, but use systemd for the rest of the processes become unstable under resource pressure"
- Attempted but rejected after consulting https://kubernetes.io/docs/setup/production-environment/container-runtimes/
- Time: Fast (config change + restart)

## Files Involved

- **Shim wrapper**: `wrapper-go/internal/wrapper/wrapper.go` (our code)
- **Real shim**: `/nix/store/.../containerd-shim-flox-2x-1.0.1+2.1.5/bin/containerd-shim-flox-v2` (external)
- **Containerd config**: `/var/lib/rancher/rke2/agent/etc/containerd/config.toml.tmpl`
- **Installer**: `shim-installer.sh` (installs shim from flox package)
- **Debug script**: `crictl-kdns-repro.sh` (reproduces the issue)

## Next Steps

1. Try Option 3 (disable systemd cgroups) as quick workaround
2. If that works, implement Option 2 (wrapper patch) for proper fix
3. Report issue to containerd-shim-flox-2x maintainers (Option 1)

## Related Issues

- FloxHub manifest: Needed store-path instead of flake reference (fixed)
- Debug annotations: Caused shim to pause indefinitely (fixed)
- Deployment state: Never re-deployed via pulumi (still uses old config with debug enabled)
