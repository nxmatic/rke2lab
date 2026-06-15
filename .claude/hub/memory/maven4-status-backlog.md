---
name: maven4-status-backlog
description: "Maven 4 status as of 2026-06-14: still RC ONLY (4.0.0-rc-5, 2025-11-13), explicitly 'NOT safe for production', new extension API 'experimental'. Verdict: do NOT migrate rke2lab now; stay on Maven 3.9.x. User wants a dedicated deep-dive report (GA blockers, community roadmap) as its own topic later."
metadata:
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**BACKLOG / dedicated future topic (user, 2026-06-14):** "creuser M4 séparément" — token stays on 3.9.x now; M4 gets its own report later (GA-blocking issues, community feedback, roadmap).

## Facts established (2 authoritative sources: live download.cgi + history.html)
- **Maven 4 = RC ONLY**: latest is `4.0.0-rc-5`, released **2025-11-13**. download.cgi says explicitly "**NOT safe for production use**". Requires JDK 17.
- **No GA**. The RC series has dragged ~13 months (rc-1 Nov 2024 → rc-5 Nov 2025) with NOTHING new in the 7 months since rc-5.
- **Maven 3 actively maintained in parallel**: `3.9.16` shipped **2026-05-13** (a month before this audit) — clear signal the community does NOT consider 4 ready.
- **The new Maven 4 API is itself "experimental phase in 4.0.0 — do not use unless you precisely know what you are doing"** (official whatsnew doc). So even migrating, the extension API to target is unstable.

## Why it matters to the token work
M4 changes exactly the two areas the credential-resolver touches:
1. **Injection**: Plexus DI REMOVED, pure JSR-330. devenv already uses `@Inject/@Named/@Singleton` → survives. BUT its `plexus/` foreign-packages uses `org.codehaus.plexus.logging.Logger` + REFLECTION on `DefaultClassRealmManager.world` → **breaks in M4** (validates freezing foreign-packages, see [[maven-github-token-resolution]]).
2. **Credentials**: M4 redoes encryption entirely (`mvnenc`, external vaults). But that's static-encrypted, NOT fresh-from-command — does NOT touch our "intercept the built Settings and setPassword" mechanism, which is version-neutral.

## Verdict (mine, stated firmly)
Do NOT migrate rke2lab to Maven 4 now: RC non-prod + stagnant 7mo + community maintaining 3.9.x + experimental extension API. rke2lab runs JDK 25; Maven 3.9.16 supports JDK 8+ fine via the flox toolchain. The credential-resolver targets **Maven 3.9.x EventSpy/LifecycleParticipant API**; it's ~150 lines so re-targeting M4 when GA matures is trivial. See [[maven-github-token-resolution]] + [[maven-extension-ecosystem]].
