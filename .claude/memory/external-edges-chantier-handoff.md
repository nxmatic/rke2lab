---
name: external-edges-chantier-handoff
description: "RESUME POINT (2026-06-22 evening) for the external-edges chantier — replicating the <target>-edge pattern across the 6 system boundaries. pulumi-edge SHIPPED (the template). Two worktrees open: refactor/osgi-boot-single-source (IN PROGRESS, the prerequisite — scan META-INF/bundles by a Provide-Capability marker, not names) and feature/ssh-to-age-edge (PAUSED behind it). Order: boot-single-source → ssh-to-age-edge → dbus-systemd (host) → incus/cluster/host-filesystem (playable). Integrate into design/pre-integration, NEVER main (main = final destination only)."
metadata:
  node_type: memory
  type: project
---

## What this chantier is

Replicate the *external edge* extraction (the `<target>-edge` template) across every system boundary,
per `docs/architecture/patterns/frontier-playability-model.adoc` +
`docs/architecture/patterns/port-edge-domain-ownership.adoc`. An edge = a domain/consumer's face toward
a system OUTSIDE ours, mutualised by target, reached through a port the *consumer* owns (the edge
implements it, owns no port). The 6 targets: pulumi, ssh-to-age, dbus-systemd, incus, cluster, host-fs.

## State (2026-06-22 evening) — base design/pre-integration @ a2c81590

SHIPPED + integrated into design/pre-integration:
- *pulumi-edge* (the TEMPLATE) — `host/pulumi-edge` consolidates every Pulumi contact (read+write),
  the stray `PulumiInterventionLedgerWriter` rapatriated. Handoff
  `docs/architecture/osgi/pulumi-edge-handoff.adoc` = the extraction recipe.
- the port/edge/domain doc (3 roles, one verb each: domain CALLS, port DECLARES, edge PROVIDES; the
  port is owned by the consumer, the edge owns none; world is DERIVED from the consumer's world).
- *osgi-boot-single-source* — the embedded-bundle hand-list is GONE. Each embeddable bundle declares
  `Provide-Capability: io.nxmatic.rke2lab.embed; type=model`; `OsgiRuntime.scanEmbeddedModelBundles()`
  reads `META-INF/bundles/` manifests and installs whatever declares it (filter on a CAPABILITY, never
  names — names slide on rename). The Java literals are gone: `SeedRuntime.bootingEmbedded()` takes no
  arg, the 3 call-sites pass no jar name. Build green (3 execs + their `EmbeddedBundlesBootTest`). Handoff
  `docs/architecture/osgi/osgi-boot-single-source-handoff.adoc`; the boot-trap NOTE in
  port-edge-domain-ownership.adoc is flagged→resolved. (Open sub-point in its handoff: the shade-`exclude`
  can't be scanned (shade runs pre-boot) — derived from staging, or documented as the two pom faces of one
  declaration.) This was the PREREQUISITE that unblocks ssh-to-age-edge.

## OPEN — order matters

1. **feature/ssh-to-age-edge (NEXT — its prerequisite is now integrated).** Worktree at ac61c50c, clean;
   rebase it on the new design tip first (boot-single-source landed). The first external edge in the OSGi
   WORLD (its consumer `DefaultManifestSynthesisService` is a `@Component`; world is derived from the
   consumer). Fixes the sops defect: `SopsAgeSecretManifestsUnit.doSynthesize` currently FETCHES the SSH
   key + shells `ssh-to-age` mid-synthesis (line ~160). Fix = the `@Component` runs a PRE-SYNTHESIS pass
   calling the converter, binds the age key into `ManifestSynthesisContext` (the profile holder, same
   channel as IncusIdentityMaterial — NOT ManifestsUnitContext), and doSynthesize only reads it. The
   contact materialises as `ssh-to-age-edge` (OSGi world, playable ProcessBuilder) + a `SshToAgeConverter`
   port owned by the consumer; NO `-core`, NO ssh-to-age-port. Adding this bundle now = 1 staging
   artifactItem + the embed capability in its bnd, ZERO Java (thanks to boot-single-source).

2. **THEN the remaining edges:** dbus-systemd-edge (host, non-playable), then the playables
   incus-edge / cluster-edge / host-filesystem-edge.

## Working rules (learned this session)

- *Check the workspace before integrating* — the jGiven "merge on main" episode was because the active
  workspace was main, not pre-integration. 1 workspace = 1 worktree = 1 conversation.
- Integrate into `design/pre-integration`, NEVER `main` (main = final destination, only when the whole is
  done). Branch each feature DIRECTLY off design/pre-integration → squash-merge has no false conflicts
  (the doctor merge exploded because design & refactor were independently rebased).
- Never `mvn install`; verify by reactor `-am clean package -Dmaven.build.cache.skipCache=true
  -DskipTests=false` + count surefire; no @Deprecated/shim (delete the old path same change); each module
  keeps its `<description>`; no runtime mutation.

See [[doctor-internal-edge-debt]] (the prior chantier, integrated) [[jgiven-osgi-testkit-shipped]]
[[osgi-system-export-resolution-only]] [[osgi-runtime-r4-boot-seam-state]].
