# Architecture Diagram Impact Analysis: BDD Scenarios as ComponentResource

**Date:** 2026-06-04
**Context:** Migration from "scenarios as separate verification gates" to "scenarios as Pulumi ComponentResource"

## Summary

23 documents contain mermaid diagrams. Impact categorized as:
- **HIGH** - Diagrams show provisioning/verification flow, need updates
- **MEDIUM** - Diagrams reference Pulumi/stack but indirectly affected
- **LOW** - Diagrams unaffected (domain-specific, no provisioning flow)

## High Impact (Require Updates)

### 1. `docs/bdd-diagnostic-pattern.adoc` ✅ UPDATED
**Status:** Already updated with before/after diagrams
**Diagrams:** 3 diagrams showing scenario architecture

### 2. `docs/bootstrap-contract.adoc` ✅ UPDATED
**Status:** Verification Stage section rewritten as "BDD Scenarios as Infrastructure Resources"
**Changes:** Added ComponentResource pattern, provisioning/drift flows

### 3. `docs/vcluster-gitops-architecture.adoc` ❌ NEEDS REVIEW
**Impact:** Shows Pulumi bootstrap flow
**Diagrams:**
- Infrastructure topology (master, peers, vClusters)
- Bootstrap flow: "Pulumi (seed-master) → CDK8s (manifests) → RKE2 cluster"
**Change needed:** Bootstrap flow should show scenarios wrapping resources

### 4. `docs/provisioning-slice-architecture.adoc` ❌ NEEDS REVIEW
**Impact:** Shows `IncusResourceBootstrap` orchestration
**Diagrams:**
- Component diagram: Bootstrap → Slices → Storage → Runtime
- Sequence diagram: Bootstrap provisioning flow
**Change needed:** Show scenarios as resources, not separate verification

### 5. `docs/manifests-architecture.adoc` ❌ NEEDS REVIEW
**Impact:** Shows manifest synthesis and application flow
**Diagrams:** Likely shows verification/application stages
**Change needed:** Manifests applied through scenario resources

### 6. `docs/rke2-install-phases.adoc` ❌ NEEDS REVIEW
**Impact:** Shows RKE2 installation phases
**Diagrams:** Installation workflow
**Change needed:** Installation verification through scenarios

## Medium Impact (Indirect References)

### 7. `docs/bootstrap-identity-provider.adoc` 🔍 REVIEW
**Impact:** Shows how identity flows through system
**Likely safe:** Identity pattern orthogonal to scenario architecture

### 8. `docs/cluster-api-bootstrap-requirements.adoc` 🔍 REVIEW
**Impact:** CAPI requirements and dependencies
**Change needed:** If shows Pulumi provisioning flow

### 9. `docs/systemd-architecture.adoc` 🔍 REVIEW
**Impact:** Systemd unit management
**Change needed:** SystemdAdapterScenario wraps systemd units

### 10. `docs/manifest-apply-flow.adoc` 🔍 REVIEW
**Impact:** How manifests are applied to cluster
**Change needed:** If shows Pulumi provisioning

### 11. `docs/post-bootstrap-in-cluster-ownership-plan.adoc` 🔍 REVIEW
**Impact:** Post-bootstrap handoff to CAPI
**Change needed:** If references Stage A verification

## Low Impact (Likely Unaffected)

- `docs/context-registry-architecture.adoc` - ThreadLocal context pattern
- `docs/cross-reference-navigation.adoc` - Documentation structure
- `docs/daemonset-host-assets-architecture.adoc` - DaemonSet pattern
- `docs/flake-build-entry-point.adoc` - Nix flake structure
- `docs/flox-store-resolved-runtime-and-builder.adoc` - Flox environments
- `docs/flox-webhook-design.adoc` - Webhook design
- `docs/host-slot-management.adoc` - Host slot allocation
- `docs/manifest-conditional-inclusion.adoc` - Conditional manifest logic
- `docs/manifest-domain-catalog-pattern.adoc` - Domain catalog pattern
- `docs/manifests-unit-lifecycle.adoc` - Manifest unit lifecycle
- `docs/netplan-blueprint-single-source.adoc` - Netplan synthesis
- `docs/policy-configuration-guide.adoc` - Policy configuration
- `docs/staged-post-cluster-resources.adoc` - Post-cluster resources

## Proposed Reorganization

Move architecture docs into nested folders:

```
docs/
├── architecture/
│   ├── bootstrap/
│   │   ├── bootstrap-contract.adoc
│   │   ├── bootstrap-identity-provider.adoc
│   │   ├── provisioning-slice-architecture.adoc
│   │   └── rke2-install-phases.adoc
│   ├── bdd/
│   │   ├── bdd-diagnostic-pattern.adoc
│   │   └── scenarios-as-resources.adoc (new overview)
│   ├── manifests/
│   │   ├── manifests-architecture.adoc
│   │   ├── manifest-apply-flow.adoc
│   │   ├── manifest-conditional-inclusion.adoc
│   │   ├── manifest-domain-catalog-pattern.adoc
│   │   └── manifests-unit-lifecycle.adoc
│   ├── systemd/
│   │   ├── systemd-architecture.adoc
│   │   └── host-slot-management.adoc
│   ├── cluster-api/
│   │   ├── cluster-api-bootstrap-requirements.adoc
│   │   ├── post-bootstrap-in-cluster-ownership-plan.adoc
│   │   └── vcluster-gitops-architecture.adoc
│   └── patterns/
│       ├── context-registry-architecture.adoc
│       ├── daemonset-host-assets-architecture.adoc
│       ├── netplan-blueprint-single-source.adoc
│       └── policy-configuration-guide.adoc
├── guides/
│   └── cross-reference-navigation.adoc
└── superpowers/
    ├── specs/
    └── architecture-diagram-impact-analysis.md (this file)
```

## Next Steps

1. ✅ Review this impact analysis
2. ❌ Review HIGH impact docs and update diagrams
3. ❌ Review MEDIUM impact docs for indirect references
4. ❌ Reorganize docs into nested folders
5. ❌ Update cross-references after reorganization
6. ❌ Update README.adoc with new structure
