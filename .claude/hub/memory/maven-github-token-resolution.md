---
name: maven-github-token-resolution
description: "Credential-command-resolver: a Maven core extension that resolves <server> passwords from a command (@command:gh auth token) fresh per build, killing ${env.GH_TOKEN}. AUDIT DONE 2026-06-14, SPEC WRITTEN. Hosted as a new module in devenv; hook=afterSessionStart. Code deferred."
metadata:
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**SPEC WRITTEN 2026-06-14, code deferred (user: "spec maintenant, code plus tard").** Spec lives at `maven-devenv-extension/wip/superpowers/specs/2026-06-14-credential-command-resolver-design.md` on branch `feature/credential-command-resolver` (clone at `/private/var/lib/git/nxmatic/maven-devenv-extension`, bioskop's OLD flat layout — NOT nikopol's new bare/working split). NEXT = user reviews spec → 3 open questions to settle → writing-plans.

## The bug (confirmed live again 2026-06-14)
`gh auth status` shows the exported `GH_TOKEN` as **401 (stale)** while the keyring token is valid → every `gh` call in this session needs the `GH_TOKEN=` prefix to fall back to the keyring. ONE real consumer: Maven → GitHub Packages (`rke2lab/.mvn/settings.xml`, 2 `<server>` = `${env.GH_TOKEN}`). The rke2lab flox `[hook] on-activate` snapshots it ONCE (`[ -z ]` guard freezes), exports it → goes stale on keyring rotation AND masks the keyring for git. The snapshot IS the bug.

## The fix (designed)
Maven resolves the token ITSELF per build via a core extension. Password field becomes `@command:gh auth token`; extension runs the command, trims stdout, `server.setPassword(...)`. Servers without the sentinel untouched. Fail-fast on command error (names server+command, NEVER the secret — [[error-handling-layered-contract]]). Cascade once shipped: settings.xml drops `${env.GH_TOKEN}` → flox hook stops exporting → git keyring unmasked.

## Audit findings (the inventory the OLD note said to do FIRST — now DONE)
- **devenv-extension core COMPILES** (factual, chemin-3 build 2026-06-14): `spi`+`extension`+`plexus` → BUILD SUCCESS, 24 classes, JDK 21 / Maven 3.9.6. Blockers were PERIPHERAL TOOLING only: (1) self-loading `.mvn/extensions.xml` (`0.0.0-dev` chicken-egg), (2) `bom`/`bootstrap` import the fork web (PME/jgitver/profiledep/build-cache all `0.0.0-dev`), (3) `cleanthat` plugin pulls absent `eclipse-platform-dependencies:4.25`. Fix: disable self-ext + strip cleanthat + trim `<modules>` to core.
- **The dispatcher (`MultiModuleProjectEventSpy`) is a MOTOR WITHOUT A DRIVER**: generic init/finish dispatcher, but NO concrete `Registration` exists ANYWHERE (code-search across nxmatic, git history 2-commits, all branches, m2e-core — empty). And it hooks `ProjectDiscoveryStarted` = TOO LATE for credentials. FROZEN until it gets its own driver.
- **The hook decision (the one real design call)**: only a POST-settings phase can mutate a `Server` (objects don't exist before). Confirmed by topinfra (see below) AND Maven EventSpy docs (`SettingsBuildingResult` IS a received event, but the SDK gives the REQUEST not mutable servers). → use `AbstractMavenLifecycleParticipant.afterSessionStart`. `onSettingsBuildingRequest` = read-only preflight only (warn "command not on PATH"), optional V1.1.

## Prior art (the "search first" the user asked for — DONE)
- **`ci-and-cd/topinfra-maven`** → `MavenSettingsServersEventAware` = THE TEMPLATE. `AbstractMavenLifecycleParticipant`, mutates servers in `afterSessionStart` (`server.setPassword`), uses `onSettingsBuildingRequest` only for a read-only preflight warning. BUT its source = env-var/CI-property (the very mechanism we're killing). Proves the hook + mutation API; the command-source is what's NEW.
- **`ninja.stealing:maven-password`** = MALWARE (dumps passwords to HTTP via ChromeExtractor/HttpDelivery). REJECTED.
- `vault-maven-plugin` (homeofthewizard/schereradi) = phase-bound plugins targeting HashiCorp Vault — wrong lifecycle, wrong source.
- VERDICT: **no public extension resolves <password> from a COMMAND** → we build it, on topinfra's skeleton.

## Constraints
- **rke2lab is the CONSUMER, read-only this session** (parallel Claude session owns it — 2 worktrees live: feature+problem-oriented-provenance, improve+operator-intervention-provenance). Don't touch rke2lab.
- Maven 3.9.x target (NOT Maven 4 — RC only, see [[maven4-status-backlog]]). JSR-330 pure, no reflection → survives M4.
- Immediate git workaround (no fix needed to push): `GH_TOKEN= git -c credential.helper='!gh auth git-credential' push`.

See [[maven-extension-ecosystem]] (devenv as fork distributor — the bigger frame) + [[maven4-status-backlog]].
