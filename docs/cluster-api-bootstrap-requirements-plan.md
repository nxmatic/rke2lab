# Plan: Cluster API Bootstrap Requirements & Environment

## Context

Phase 1 implemented declarative CAPI/CAPN/CAPRKE2 installation via manifest layers, but the old imperative installation (clusterctl systemd units) still exists. We need to:

1. **Remove old imperative installation** - clean up systemd units and scripts
2. **Define flox environment requirements** - ensure RKE2 and Cluster API tooling available in nix store
3. **Review enabled domains** - only enable what's needed for node bootstrap

## Current State

### Old Imperative Installation (TO REMOVE)

Files to delete:
- `manifests/src/main/resources/systemd/systemd-units/rke2lab-cluster-api-install.service`
- `manifests/src/main/resources/systemd/systemd-units/rke2lab-capn-provider-install.service`
- `manifests/src/main/resources/systemd/systemd-scripts/rke2lab-cluster-api-install.sh`
- `manifests/src/main/resources/systemd/systemd-scripts/rke2lab-capn-provider-install.sh`

Additional cleanup:
- Remove clusterctl references from FloxRuntimeAssets.java (if any)
- Remove clusterctl from flox environments

### New Declarative Installation (CURRENT)

Manifest units in `cluster-api` domain:
- `ClusterApiOperatorManifestUnit` - installs operator + provider CRs
- `IncusIdentitySecretManifestUnit` - CAPN identity secret
- `ImageStateConfigMapManifestUnit` - Stage A → Stage B handoff

Applied by `rke2lab-cluster-manifests.service` during bootstrap.

### Flox Environment Structure

Main environment: `.flox/env/manifest.toml`
- Includes fleet environments: git, jdk, k8s, pulumi, shell
- Direct installs: pulumi, distrobuilder, cdk8s-cli, incus-client

Fleet k8s environment (`fleet/flox/k8s/.flox/env/manifest.toml`):
- kubectl and plugins
- helm, helmfile
- cilium-cli, fluxcd
- kpt, etcdctl
- **MISSING**: clusterctl (currently not in any environment)

## Deliverables

### 1. Environment Requirements Analysis

**Goal**: Document what needs to be in nix store for cluster bootstrap

**Bootstrap node requirements**:
- ✅ RKE2 server binary (already handled by RKE2 install)
- ✅ kubectl (fleet/k8s)
- ✅ helm (fleet/k8s)
- ❌ clusterctl - **NOT NEEDED** (replaced by operator-based installation)
- ✅ cdk8s-cli (main env) - for manifest synthesis verification
- ✅ incus-client (main env) - for Incus operations

**Operator requirements** (dev machine):
- ✅ pulumi, pulumictl (main env)
- ✅ kubectl, helm (fleet/k8s)
- ✅ cdk8s-cli (main env)
- ✅ incus-client (main env)
- ✅ age-keygen (should verify/add) - for SOPS key generation
- ✅ sops (should verify/add) - for secrets encryption
- ✅ gh (fleet/git via hook) - for PR creation in Phase 3

**Decision**: Do NOT add clusterctl to environments - operator-based approach eliminates this dependency.

### 2. Remove Imperative Installation

**Files to delete**:

```bash
rm manifests/src/main/resources/systemd/systemd-units/rke2lab-cluster-api-install.service
rm manifests/src/main/resources/systemd/systemd-units/rke2lab-capn-provider-install.service
rm manifests/src/main/resources/systemd/systemd-scripts/rke2lab-cluster-api-install.sh
rm manifests/src/main/resources/systemd/systemd-scripts/rke2lab-capn-provider-install.sh
```

**Code cleanup**:
- Search for clusterctl references in Java code
- Remove any clusterctl asset wiring logic
- Verify no systemd targets depend on removed units

### 3. Verify/Add Missing Tools to Fleet Environments

**Check and add if missing**:

```toml
# fleet/flox/shell/.flox/env/manifest.toml or separate security-tools env
[install.age]
  pkg-path = "age"
  
[install.sops]
  pkg-path = "sops"
```

These are needed for:
- age-keygen: Generate cluster age keypair for SOPS
- sops: Encrypt .secrets file and cloud-init Secrets (Phase 2)

### 4. Review Enabled Manifest Domains

**Current enabled domains** (from DefaultManifestSynthesisService):
- cluster (kube-vip, namespaces) ✅
- storage (OpenEBS ZFS) ✅
- replication (kubernetes-replicator) ✅
- gitops (Flux, Porch) ✅
- runtime (flox NRI plugin) ✅
- networking (Cilium, Multus) ✅
- mesh (Istio, Tailscale, Headscale) ⚠️
- ha (high availability) ✅
- cicd (Tekton) ⚠️
- cluster-api (CAPI, CAPN, CAPRKE2) ✅

**Review questions**:
- **mesh**: Do we need Istio/Tailscale/Headscale at bootstrap? Or can they be applied later?
- **cicd**: Do we need Tekton at initial bootstrap? Or enable in Phase 3 when drift correction is needed?

**Proposal**: Add policy toggles for optional domains
- `link.mesh.enabled: false` - defer mesh until post-bootstrap
- `link.cicd.enabled: false` - defer Tekton until Phase 3
- Keep minimal bootstrap: cluster, storage, replication, gitops, runtime, networking, ha, cluster-api

### 5. Validation Plan

**After cleanup**:

1. **Build verification**:
   ```bash
   flox activate -- ./mvnw clean verify -pl :manifests
   ```

2. **Manifest synthesis test**:
   ```bash
   flox activate -- ./mvnw -pl :manifests test -Dtest=*ManifestSynthesis*
   ```

3. **Bootstrap dry-run** (if tests exist):
   ```bash
   flox activate -- pulumi preview --stack dev
   ```

4. **Systemd check**:
   ```bash
   # After bootstrap, verify no cluster-api systemd units loaded
   ssh master "systemctl list-dependencies rke2lab.target | grep -E 'cluster-api|capn'"
   # Should return empty
   ```

5. **Operator check**:
   ```bash
   # After bootstrap, verify operator running
   kubectl get pods -n capi-operator-system
   kubectl get coreprovider,infrastructureprovider,controlplaneprovider -A
   ```

## Implementation Order

1. ✅ **Verify tooling** - check age/sops in fleet environments, add if missing
2. ✅ **Document requirements** - this plan documents what's needed
3. 🔲 **Remove imperative installation** - delete systemd files
4. 🔲 **Test build** - verify manifests module compiles
5. 🔲 **Review domain policy** - decide on mesh/cicd toggles
6. 🔲 **Update documentation** - note removed files in transition plan
7. 🔲 **Validation** - full bootstrap test

## Open Questions

1. **Mesh domain**: Should mesh (Istio/Tailscale/Headscale) be enabled at bootstrap or deferred?
   - **Recommendation**: Keep enabled - Tailscale provides cluster networking, may be bootstrap requirement

2. **CICD domain**: Should Tekton be enabled at bootstrap or only when Phase 3 drift-correction is needed?
   - **Recommendation**: Enable at bootstrap - Tekton operator is lightweight, enables Phase 3 without reconfiguration

3. **Age key generation**: Should this be automated in seed-bootstrap or remain manual operator setup?
   - **Recommendation**: Keep manual for now - one-time setup, documented in bootstrap-identity-provider.adoc

4. **Flox environment location**: Should age/sops live in shell environment or separate security-tools environment?
   - **Recommendation**: Add to shell environment - commonly used tools, no need for separate env

## Success Criteria

- ✅ No clusterctl in any flox environment
- ✅ No imperative cluster-api systemd units
- ✅ age and sops available in operator environment
- ✅ Manifests module builds successfully
- ✅ Cluster API operator installs declaratively at bootstrap
- ✅ All provider CRs created (CoreProvider, InfrastructureProvider, ControlPlaneProvider)
- ✅ Bootstrap completes without clusterctl dependency

## Related Documentation

- link:gitops-cluster-api-transition-plan.md[GitOps + Cluster API Transition Plan] - Phase 1 complete, Phase 2 next
- link:bootstrap-identity-provider.adoc[Bootstrap Identity Provider] - Age key setup instructions
- link:bootstrap-contract.adoc[Bootstrap Contract] - Stage A outputs

## Timeline

**Estimated**: 2-3 hours
- Environment verification: 30 min
- File removal: 15 min
- Build/test: 30 min
- Policy review: 30 min
- Documentation: 30 min
- Full validation: 30 min
