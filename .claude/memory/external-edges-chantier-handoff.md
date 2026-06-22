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

## OPEN — order matters

1. **refactor/osgi-boot-single-source (IN PROGRESS, do FIRST — the prerequisite).** Worktree at
   `0561608d`, ahead of base. The defect: `META-INF/bundles/` model-bundles (manifests-core, netplan-core)
   were hand-listed at 4 sites × 3 execs (pom staging `destFileName` + pom shade-`exclude` + Java
   `bootingEmbedded` + test `embeddedBundle`); OsgiRuntime admits it (Javadoc ~L87-92). **DECISION (user):
   filter on a CAPABILITY, never names** — names slide on rename. Each embeddable bundle declares
   `Provide-Capability: io.nxmatic.rke2lab.embed; type=model` in its bnd; OsgiRuntime SCANS
   `META-INF/bundles/`, reads each manifest, installs as model-bundle whatever declares it. Boot-stack
   (pax/scr/resolver, the `*_JAR` constants) does NOT carry it → objectively excluded; do not touch it.
   ALREADY DONE in 0561608d: the capability is on manifests-core + netplan-core, the scan exists.
   STILL TODO / verify: `bootingEmbedded`/`embeddedBundle` still appear in 5 files (BootstrapStage, Main,
   SynthesisCommand, SeedRuntime, OsgiRuntime) — confirm whether those are the *method defs* (legit to
   keep) vs the *literal call-sites* (must die); the shade-exclude can't be scanned (shade runs
   pre-boot) → derive it from the staging list if Maven allows, else document staging+exclude as the two
   pom faces of one declaration; scan determinism (model-bundles share START_LEVEL_BUNDLES=3, sort if
   needed); degrade cleanly when `hasEmbeddedBundles()` is false (reactor/test). Make the "boot trap"
   NOTE in port-edge-domain-ownership.adoc go flagged→resolved; write
   `docs/architecture/osgi/osgi-boot-single-source-handoff.adoc`.

2. **feature/ssh-to-age-edge (PAUSED behind #1).** Worktree at ac61c50c, clean. The first external edge
   in the OSGi WORLD (its consumer `DefaultManifestSynthesisService` is a `@Component`; world is derived
   from the consumer). Fixes the sops defect: `SopsAgeSecretManifestsUnit.doSynthesize` currently FETCHES
   the SSH key + shells `ssh-to-age` mid-synthesis (line ~160). Fix = the `@Component` runs a
   PRE-SYNTHESIS pass calling the converter, binds the age key into `ManifestSynthesisContext` (the
   profile holder, same channel as IncusIdentityMaterial — NOT ManifestsUnitContext), and doSynthesize
   only reads it. The contact materialises as `ssh-to-age-edge` (OSGi world, playable ProcessBuilder) +
   a `SshToAgeConverter` port owned by the consumer; NO `-core`, NO ssh-to-age-port. After #1 lands,
   adding this bundle = 1 staging artifactItem + the embed capability in its bnd, ZERO Java.

3. **THEN the remaining edges:** dbus-systemd-edge (host, non-playable), then the playables
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
