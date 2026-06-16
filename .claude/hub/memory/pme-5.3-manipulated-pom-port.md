---
name: pme-5.3-manipulated-pom-port
description: "PME 5.3 port chantier — revive the 'build & attach the manipulated POM without overwriting the original' mechanism (Bridge auto-injects a Mojo doing setPomFile+setFile) onto the renamed jboss.pnc.mavenmanipulator 5.3 base. Spec written, port not yet started. This is the bootstrap mechanism for the whole maven-extension fleet."
metadata:
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**STATE 2026-06-14: spec written + committed, code port NOT started.** Resume by porting the Mojo first (smallest-first per spec).

## Why this matters (the chain)
This grew out of [[maven-github-token-resolution]] (kill `${env.GH_TOKEN}`). The user's real wish is to revive his **maven-extension fleet** ([[maven-extension-ecosystem]]) — devenv as a distributor of forked extensions. The KEY enabling mechanism = PME able to rewrite poms to `0.0.0-dev` at runtime **without touching source poms**, which breaks the circular bootstrap (a RELEASED PME rewrites versions, so forks don't need to build themselves to exist). PME is the lever.

## What was ABANDONED (don't retry)
The full devenv revival was a PIT: a half-refactored system of mutually-coupled `0.0.0-dev` forks. Discovered PME `develop` depends on `maven-devenv-common` (a devenv module that NO LONGER EXISTS) + `maven:3.9.9-dev` + galley `0.0.0-dev`. Each fork pulled another. **Tri rule learned: fork/build-`0.0.0-dev` ONLY repos carrying real patches; pull everything else RELEASED from Central.** jhttpc/galley = NO patches → Central (jhttpc 1.12, galley 1.21, web-commons-bom 29). Only PME + build-cache + devenv carry real patches.

## The PME repo state (`/private/var/lib/git/nxmatic/pom-manipulation-ext`)
- **`develop`** = NOW the 5.3 stable base (`571a55c6`), package **`org.jboss.pnc.mavenmanipulator`** (upstream RENAMED from `org.commonjava.maven.ext` between the old patches and 5.3 — this is why a raw rebase was impossible).
- **tag `v5.3.0-nxmatic`** = the release point (5.3 built locally into ~/.m2; NOT on Central, only 5.0 is). Detachable for release build. There's a worktree `.git-worktree.d/v4.3` (misnamed, actually 5.3) that did the local install.
- **`archive/commonjava-patches`** (`cad7703d`) = the ORIGINAL 15 patches over merge-base `f56f5144`, package `org.commonjava.maven.ext`. SOURCE OF TRUTH for the patch to port. Mostly noise (pom-effective.xml, mvnw, reformatting, `[wip] should split`, `[git] don't know`); the SIGNAL = 3 `[feat]` commits: `da313acc` (run with manipulated pom without updating original — THE key one), `f6754aa2` (version overwrite, pomIO self-managing), `ae8bbe60` (PME report re-entrant).
- **`feature/manipulated-pom-passthrough`** (`e7e83354`) = current work branch, holds the SPEC at `wip/superpowers/specs/2026-06-14-revive-manipulated-pom-passthrough.md`. (Spec under wip/ → wip-guard blocks it on develop, MUST stay on a feature branch.)

## The mechanism to revive (proven, from archive)
5.3 already has `manipulationWriteChanged=false` (skip writing the original) but then Maven builds the UN-manipulated original — the missing half. The fix:
1. EventSpy (`ProjectDiscoveryStarted`) → `ManipulationManager.scanAndApply` applies, does NOT rewrite original.
2. **`ManipulationExtensionBridge`** auto-injects a plugin execution bound to phase `initialize` (override `-Dpom-manipulation-replacement-phase`).
3. **`AttachManipulatedPOMs`** Mojo runs at that phase, per project: writes manipulated model to a TEMP pom, then `project.setPomFile(temp)` (Maven BUILDS on manipulated) + `project.setFile(temp)` (install/deploy ATTACHES manipulated). BOTH calls needed.
- **EventSpy-direct does NOT work** (projects not frozen at ProjectDiscoveryStarted → setFile overwritten). That's WHY the Mojo+phase design exists. Keep Bridge+Mojo.

## Scope (honest, ~12 files not "3 classes")
New classes in archive to re-package to `org.jboss.pnc.mavenmanipulator.*`: `bridge/ManipulationExtensionBridge`, `manip/AttachManipulatedPOMs`, `manip/ManipulationEventSpy`+`ManipulationModelReader`+`CacheMatcher`, `manip/ManipulationMultiModuleLifecycleParticipant`, `io/GalleyMultiModuleLifeCycleParticipant`, `common/ManipulationComponent`, `core/enterprise/GalleyProducerModule`, `io/resolver/GalleyCacheFactory`. Integration points (modify): `ManipulationManager`, `ManipulationSession`, `ManipulatingEventSpy`, `PomIO`, `ModelIO`, `ExtensionInfrastructure`.
**THE REAL RISK = the CDI/session-scope pieces** (commit `[wip] should split, mainly maven injection session scope issues`): they assume the commonjava container wiring; 5.3 jboss.pnc may have restructured scoping. The Bridge+Mojo+EventSpy trio is load-bearing; the CDI pieces are the risky-to-validate part.

## NEXT (resume here)
Port order (smallest-first, build each step): (1) Mojo `AttachManipulatedPOMs` alone (most self-contained), (2) Bridge + wire from 5.3 EventSpy/ManipulationManager, (3) CDI/scope pieces only as far as needed, validating against 5.3's container. Build env: `mvn` = `/nix/store/rhdcf51rndrisr6wz4lhbib6ghhfqrn4-maven-3.9.11/bin/mvn`, JDK21 `JAVA_HOME=/nix/store/010afq5cqbsy60r86hmg9cjjphg2xb20-zulu-ca-jdk-21.0.8/...`, settings `-s /private/var/lib/git/nxmatic/maven-devenv-extension/.mvn/settings.xml` (mirror excludes ecentral). All `gh`/git need `GH_TOKEN=` prefix (exported token is stale 401, keyring valid).
