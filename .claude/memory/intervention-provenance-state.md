---
name: intervention-provenance-state
description: "★ PARKED 2026-06-14, branch improve/operator-intervention-provenance NOT merged (16 commits, all on-branch). A first-class Intervention-with-Provenance for the doctor so efficacyOf stops crediting un-applied prescriptions. Increments A (Intervention types) + B (Expectation) + C1/C2b (Layout/StackCoordinate) BUILT & reviewed; C3 writer + 2 spikes BUILT then SUPERSEDED (wrong model — see [[model-substrate-alignment]]). HALTED because the chantier outgrew itself: the model became 'specialist-as-ledger + multi-time consultation + two memories' (see [[specialist-as-ledger-northstar]]). RESUME = FRESH session: finish that brainstorm → write ONE spec of the full model → decide what of A/B/C survives → re-spec & build. Nothing merged; the fresh brainstorm decides what to keep."
metadata: 
  node_type: memory
  type: project
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**★★ PARKED — RESUME POINT (read this first, 2026-06-14).** The branch is parked, NOT merged, by
deliberate decision: A+B are LATENT (Expectation is written/read but nothing consumes it yet — D was
never built), and the fresh brainstorm may relocate where the Expectation lives, so merging latent code
that could churn was rejected. The chantier outgrew its original scope mid-flight — it is now the
"specialist-as-ledger" model.

**★ WHERE THE BRANCH LIVES NOW (workspace cleanup, 2026-06-14).** The branch was extracted from the main
checkout into a DEDICATED worktree: `.claude/worktrees/improve+operator-intervention-provenance` (tip
b3ede92d, clean). The main checkout is back on `main`, read-only ([[worktree-per-conversation]] rule). The
branch's sole role now = **a cherry-pick reservoir** — do NOT rebase or merge it (a rebase would rewrite
the SHAs and defeat cherry-picking the KEEP-candidate commits listed below). On creation the worktree's
sops secrets came out encrypted and were re-smudged → see [[sops-worktree-smudge-noise]]. **RESUME in a
FRESH session, NOT by continuing C/D here:**
1. Re-read [[specialist-as-ledger-northstar]] (the full model: two memories, multi-time consultation =
   the deferred agenda loop, ledger-backed specialist whose domain is outside seed-master's field) and
   [[model-substrate-alignment]] (why the C3 writer's mechanism was wrong).
2. FINISH that brainstorm: Diagram 0 vocabulary was drawn (.claude/claude-preview.adoc, may be stale);
   the OPEN fork was "is a LedgerBackedSpecialist a subtype of Specialist (same diagnose(Referral) seam)
   or consulted differently" — the multi-time-consultation insight reframes it: the drift trigger is the
   generalist's FINAL-SYNTHESIS step noticing expected-vs-observed drift, not a bolted-on mode.
3. Write ONE spec of the full model, THEN decide what of the parked A/B/C survives (cherry-pick/rebuild
   from this branch), THEN re-spec & build.
WHAT'S ON THE BRANCH (16 commits, last = b3ede92d): KEEP-candidates A (Provenance/Intervention/Reader/
Ledger), B (ExpectationPredicate/Expectation/derive+persist B3/read-into-Visit B4), C1 Layout, C2b
StackCoordinate. SUPERSEDED (the fresh brainstorm likely drops): C3 InterventionLedgerWriter (export+
union+catch = wrong model), the 2 spikes (f2a7ba18, b3ede92d). The spike b3ede92d's FINDING is still
valuable (per-resource+fold works, up() clean with no top-level export) — keep the knowledge, drop the file.

---

**WHAT & WHY.** The operator-intervention gap proven on real master ([[master-provisioning-state]]):
a cure that came from an out-of-band `nft delete` leaves no trace, so `MedicalRecord.efficacyOf`
would credit the never-applied `restart-unit` prescription. Fix = record any actor's **Intervention**
with **Provenance** (`pulumi-engine` = administered / `operator-manual` = declared / `external-change-detected`
= inferred by drift), persisted in a dedicated `intervention-ledger` Pulumi stack. This is the data source
a future drift/provenance specialist consults (the ad-hoc-OPERATOR→codified-specialist rung of the
recruit-a-specialist gradient). Spec: `wip/superpowers/specs/2026-06-13-intervention-with-provenance-design.md`;
plan: `wip/superpowers/plans/2026-06-13-intervention-with-provenance.md`.

**APPROACH = drift-first, expected-state at the centre.** Each prescription records an `Expectation`
(typed `ResolutionPredicate` now, sealed-interface FINGERPRINT SEAM for later). At the next run the
`DriftDetector` joins Expectation × Observation × ledger: a resolved symptom with NO administered
(`pulumi-engine`) intervention and NO declared `operator-manual` one in the window → infers an
`external-change-detected` Intervention. `efficacyOf` then marks such attempts **confounded**, not effective.

**DONE on-branch (subagent-driven, every task passed spec + code-quality review):**
- Increment A: `Provenance` enum, `Intervention` record+`toOutputMap`, `InterventionReader.fromOutputMap`,
  `InterventionLedger` (time-sorted, `between(fromExclusive,toInclusive)` window fold).
- Increment B: `ExpectationPredicate` sealed iface + `ResolutionPredicate`; `Expectation` record+reader;
  `ConsultationReport.expectations(Instant)` derivation + per-node `expectations` output in
  `SystemdAdapterResource` (recordedAt = `deployment().timestamp()`, NO hidden `Instant.now()`); read back
  into the `Visit` (4th component — all 11 `new Visit(` sites migrated, no compat shim).
- Increment C so far: `InterventionLedgerLayout` (SoT: PROJECT/STACK/OUTPUT_KEY + `stacksDir` delegate);
  C2 SPIKE settled the write path.

**★ C2 SPIKE FINDING (the load-bearing unknown, now settled):** the ledger writer = **Automation-API
inline program + `up()`** (NOT export/import — `importStack` updates live state but writes no history pair,
and `StackHandle.forBackend` is a HISTORY reader → invisible). Three facts the writer must carry: (1) the
Java SDK's post-`up()` `getOutputs()` THROWS `JsonSyntaxException` on an array-valued top-level output
(deserializes as scalar string) — CATCH & IGNORE, state is written, read back via `StackHandle`; (2) append
= read-union-rewrite (the inline program replaces the output wholesale); (3) `outputsNamed("interventions")`
returns `List<Object>` whose single element is the `List<Map>` of entries (flatten one level, like B4). `pulumi`
CLI is on PATH in flox (v3.225.1), `up()` runs headless. Tests use JUnit `@TempDir` = per-test isolated
backend (the runtime isolation, mktemp-equivalent).

**★ MID-EXEC CORRECTION (user, during C2) = C2b.** The spike's param-order bug (`createOrSelectStack(project,
stack, …)` — two adjacent same-typed Strings, swapped, wrote under `<project>/<project>/`) is the textbook
"2+ same-typed params → typed value" smell. Fix = a `StackCoordinate(project, stack)` record (validated like
`SchemaRef`), exposed via `InterventionLedgerLayout.ledger()`; C3/C4 take a `StackCoordinate` and unpack at the
ONE Pulumi-API line, swap impossible. Scope discipline: do NOT change the shared
`StackHandle.forBackend(Path,String,String)` signature (medical-record reader uses it too → backlog). Validates
[[works-best-from-concrete-code]] — the smell only surfaced in concrete spike code, the correction propagated
back into the plan.

**NEXT — SUPERSEDED by the PARKING banner at the top of this note.** The old linear C3→C5→D path is
ABANDONED (C3's mechanism was the wrong model). Do NOT resume it. Resume = the fresh-session brainstorm
described in the parking banner ([[specialist-as-ledger-northstar]] is the model to finish + spec).

**Adjacent git state (2026-06-14):** `origin/main` is current and PUSHED — the workspace-cleanup session
pushed 3 chore commits (gitignore + memory notes) via the `gh` credential-helper workaround
(`GH_TOKEN= git -c credential.helper='!gh auth git-credential' push`), so the earlier "auth failing / not
pushed" state is resolved for routine pushes (the [[maven-github-token-resolution]] backlog still stands
for the Maven build path). NOTE: the live-probe fix ([[master-provisioning-state]]) lives on its own
branch `fix/systemd-live-probe-contract`, NOT merged. This improve/ branch is independent of both.
