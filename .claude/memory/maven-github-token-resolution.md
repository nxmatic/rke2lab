---
name: maven-github-token-resolution
description: "BACKLOG — replace ${env.GH_TOKEN} in Maven settings.xml with a fresh-per-build token from `gh auth token` via a core extension (EventSpy password-from-command); fixes the stale-snapshot bug. Surfaced during the wip-guard chantier. NEXT = user inventories his ~19 maven instrumentation repos first."
metadata: 
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**PARKED 2026-06-13 as a fresh-conversation topic.** Surfaced while pushing nix-darwin-home `develop` (see [[global-wip-guard-hooks-state]]): a `git push` failed because an exported `GH_TOKEN` was a STALE snapshot masking the valid keyring token.

## The bug (root cause, confirmed not assumed)
`GH_TOKEN` has ONE real consumer: **Maven → GitHub Packages**. `rke2lab/.mvn/settings.xml` has 2 `<server>` entries (`github-nxmatic-java-systemd`, `github-nxmatic-java-bbox-api-client`) whose `<password>` is `${env.GH_TOKEN}`, username `nxmatic` (GH Packages ignores username). Maven needs the token IN THE ENV — can't use git's credential helper.

To feed Maven, the **rke2lab `[hook] on-activate` flox** snapshots it:
```
if command -v gh && [ -z "${GH_TOKEN:-}" ]; then GH_TOKEN="$(gh auth token)" && export GH_TOKEN; fi
```
The `[ -z ... ]` guard freezes it ONCE and never refreshes; being exported, it then (a) goes stale when `gh` rotates the keyring, (b) MASKS the fresh keyring for git (git/gh prefer GH_TOKEN over keyring). Introduced commit `846a9d02` (2026-05-21, "add GITHUB_OWNER and GH_TOKEN env setup" — for Maven GH Packages).

Git side is already correct: credential.helper = `gh auth git-credential` (on-demand keyring) — needs NO GH_TOKEN.

## Provenance map (where each piece lives)
- **The snapshot (the bug)** → rke2lab `.flox/env/manifest.toml` `[hook]` ONLY (not in fleet).
- **Consumers of GH_TOKEN** → `fleet/flox/git` (generated from `fleet/flox/.kustomize/flox.yaml`, a kpt/kustomize FloxEnvironment source — fix goes to the YAML, not the generated manifest.toml): `[hook]` does `gh auth login --with-token` + `gh auth setup-git`; `[profile]` derives GITHUB_LOGIN/OWNER/REPOSITORY. nix-darwin-home flox template `.flake/env/flox-manifest.tmpl.toml` `[profile]` DUPLICATES the GITHUB_* derivation.
- **The GOOD pattern already in repo** → `nix-darwin-home/modules/darwin/github-mcp-proxy.nix`: never exports a token, declares `tokenCommand = "gh auth token"` resolved on-demand + 300s TTL cache. The model to emulate.
- rke2lab flox `[include]`s `../fleet/flox/{git,k8s,keyhole,pulumi,shell}`.

## Chosen direction (user approved the route, NOT yet the full design)
**Maven core extension: "password-from-command"** (user's real wish — kill `${env.GH_TOKEN}` entirely).
- Maven is **3.9.12** (Maven-3 API: Plexus/Sisu, AbstractMavenLifecycleParticipant / SettingsDecrypter). Maven stock has NO native command-based password interpolation — must be built.
- Hook point: `.mvn/extensions.xml` (ALREADY EXISTS in rke2lab with maven-build-cache-extension 1.2.0 — just add an entry). A core extension intercepts built settings, and for servers whose `<password>` carries a marker (e.g. `@command:gh auth token`) replaces it with the command's trimmed stdout, FRESH per build. Servers without the marker untouched. username stays plaintext (worthless).
- Cascade once shipped: settings.xml drops `${env.GH_TOKEN}`; rke2lab flox hook STOPS exporting GH_TOKEN (kills the snapshot-bug → git keyring clean); Maven gets fresh token via gh = single source of truth.
- Rejected alternatives: mvnw `exec env GH_TOKEN=$(gh auth token)` (pragmatic but keeps ${env.*}); process-substitution settings (`mvn -s <(...)`, no new component but settings becomes a template); Maven password-encryption (static token — doesn't solve rotation).

## NEXT STEP the user wants FIRST
**Inventory his existing Maven instrumentation repos** — he spent significant time on this pre-Claude and wants to see what's still current before building anew. The ~19 nxmatic maven-ish repos (via `GH_TOKEN= gh repo list nxmatic`):
- **`maven-devenv-extension`** (2024) — EventSpy + MultiModuleProjectLifecycleParticipant + foreign-extension-packages scaffolding (noname.maven.devenv.*). The SCAFFOLDING for hosting a core extension, not the feature. Strong reuse candidate.
- **`pom-manipulation-ext`** (2025) — preprocesses poms via EventSpy core extension. The MECHANISM pattern (applied to poms, not credentials).
- **`maven-project-settings-extension`** — loads project-level settings.xml; passwords copied verbatim (doesn't resolve).
- **`ozymandias`** — collection of maven plugins/extensions.
- Others: maven-versioning-extension, maven-external-version, jgitver-maven-plugin, maven-build-cache-extension (fork), pom-manipulation, maven-profile-activator-extension(-fork), gh-maven, mvn2nix, nix-maven-mvnd, maven-mvnd, maven-enforcer (forks).

## Constraints for the fresh conversation
- **rke2lab has a PARALLEL Claude session** — do NOT modify rke2lab until it merges to main (same blocker as wip-guard Task 6 in [[global-wip-guard-hooks-state]]).
- Pattern `${env.GH_TOKEN}` likely repeats in other Java repos (java-systemd, java-bbox-api-client) → decide mono-repo vs shared fix.
- Immediate workaround that WORKS for git (no fix needed to unblock a push): `GH_TOKEN= git -c credential.helper='!gh auth git-credential' push …`.
- Design was paused at Section 1/4 (architecture approved verbally); resume by writing the spec once the repo inventory clarifies what to reuse vs build.
