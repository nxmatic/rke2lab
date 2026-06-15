---
name: maven-extension-ecosystem
description: "devenv-extension was conceived as a DISTRIBUTOR/coordinator of Maven extensions (its bootstrap aggregates PME+jgitver+build-cache+profiledep), not just the host of its dormant dispatcher. The real future value = ship the user's FORKED+PATCHED extensions to rke2lab. First concrete payload = the build-cache m2e patches."
metadata:
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**BACKLOG / the bigger frame behind the token work (surfaced 2026-06-14).** When the user resisted "letting devenv go to oblivion," the question "do devenv + my build-cache patches solve rke2lab's cache problem?" reframed devenv's reason to exist.

## The reframe
devenv-extension's `bootstrap/` module ALREADY aggregates heterogeneous forked extensions — `maven-devenv-bom` lists PME, jgitver-maven-plugin, maven-build-cache-extension, maven-profiledep-extension, all rebuilt locally as `0.0.0-dev`. So devenv was NEVER just the host of its (driverless, dormant) `MultiModuleProjectEventSpy` dispatcher — it was designed as a **coordinator/distributor of Maven extensions**, published to GitHub Packages, consumed by the user's projects. THAT is a real need (ship patched forks), not speculation — which is what justifies keeping devenv alive (vs the dispatcher alone, which would be speculative). The credential-resolver ([[maven-github-token-resolution]]) is its first NET-NEW passenger; the build-cache patches are its first FORK payload.

## The build-cache patches (the user's real work, found 2026-06-14)
`nxmatic/maven-build-cache-extension` branch `develop` has 3 Lacoin-authored commits (the rest is dependabot):
- **`[feat] m2e compatibility`** (2023-12-10): in `MavenProjectInput.resolveArtifact`, when `resolved.getFile()` is a DIRECTORY (`target/classes` of a reactor sibling under m2e) not a jar, reconstructs the jar/zip path before hashing → stabilises the cache key. Touches EXACTLY the dependency-hash that keys the cache.
- **`[fixup] NPE`** (2024-02-03): `LifecyclePhasesHelper` robust when `getExecutionListener()` is null (the Eclipse/m2e case).
- `integrated with nuxeo/nos` (2023-08-23): local deployment, off-topic.
Together = "make build-cache work under m2e/Eclipse."

## The catch (before adopting into rke2lab)
- **Base is OLDER**: `develop` is on `1.1.1-SNAPSHOT`; rke2lab runs stock apache **`1.2.0`**. compare = **+3 / -172**. Adopting the fork as-is REGRESSES rke2lab 172 upstream commits for 2 patches → must REBASE the 2 patches onto 1.2.0+ (or apache HEAD).
- **Different symptom**: the m2e patch fixes "resolved file is a directory" hashing. rke2lab's actual cache pain (per [[rke2lab:build-verification-gotchas]]) is the **shaded-jar drift** (a shade-plugin jar whose hash doesn't track its content → stale replay; user runs `skipCache=true` permanently to dodge it). NOT proven the existing patch fixes THAT — needs diagnosis. The shaded-jar drift is itself parked-unexplored.

## When to act
Parked until the token ships OR the user has time to revive devenv. Then: (1) rebase build-cache patches onto 1.2.0+, (2) host credential-resolver as a module, (3) publish to GH Packages, (4) rke2lab consumes. Dispatcher stays dormant. See [[maven-github-token-resolution]] + [[rke2lab:build-verification-gotchas]].
