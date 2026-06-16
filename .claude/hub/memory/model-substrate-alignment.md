---
name: model-substrate-alignment
description: "Design principle caught 2026-06-13 during the intervention-ledger build: if a round-trip must WRITE through one path and READ by bypassing that same path, the model is not aligned with its substrate. The bypass IS the tell. Corollary: a Pulumi top-level ctx.export output is a scalar VALUE (read by the SDK's scalar-string deserializer), NOT an append-journal; per-resource registerOutputs + history-fold is the journal mechanism (what the medical record already does)."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**THE TELL (general).** When persisting+reconstructing state, if you find yourself WRITING through
mechanism X but READING by *bypassing* X (a different code path), stop — the model is mis-fitted to
its substrate. The bypass is not a clever workaround; it is the symptom. An aligned model reads and
writes through the same conceptual channel.

**WHERE IT BIT US (2026-06-13, intervention-ledger, branch improve/operator-intervention-provenance).**
The ledger writer used the Pulumi Automation API: write = `up()` with `ctx.export(OUTPUT_KEY, list)`
(a TOP-LEVEL output), read = `StackHandle.currentSnapshot().outputsNamed(...)` which reads the raw
checkpoint and *bypasses* the SDK's `getOutputs()`. Three smells, all ONE cause:
1. `up()`'s post-deploy `getOutputs()` THROWS on EVERY successful write (the SDK deserializes each
   top-level output as a scalar String; our value is a JSON array → `JsonSyntaxException`). We were
   catching an exception that fires on the happy path = exception-as-control-flow.
2. To tell a benign throw from a real deploy failure we had to DECOMPILE the SDK
   (`CommandException extends AutomationException` for real failures; plain `AutomationException`
   with a `JsonSyntaxException` cause for the benign quirk) — reverse-engineering vendor internals =
   brittle across versions.
3. read-union-rewrite (read prior list, append, re-export the whole thing) — needed ONLY because a
   top-level output keeps just the LATEST value, so we hand-rolled accumulation.

**THE ALIGNED MODEL (the twin that already works).** The medical record persists the SAME shape of
data correctly: write = `registerOutputs(...)` = a PER-RESOURCE output living in the checkpoint's
resource graph; read = `MedicalRecordReader` FOLDS THE HISTORY (one Visit per update). It never
touches `getOutputs()`, never throws, never unions — each update naturally carries its own entry.
So the ledger should be the medical record's twin: each `record-intervention` = one update writing
its intervention as a per-resource output; `load()` = fold the ledger stack's history. That kills all
three smells at the root (no top-level export → no throw to catch → no SDK decompile; history-fold →
no union-rewrite), and reuses the proven, trusted mechanism.

**DISTINCTION TO REMEMBER.** Pulumi top-level `ctx.export` output = a scalar VALUE, read back by the
Java SDK's scalar-string deserializer (chokes on arrays/objects). Per-resource `registerOutputs` =
structured data in the resource graph, read via `outputsNamed`/history-fold. For a JOURNAL (append
log of events), the per-resource + history-fold mechanism is correct; a top-level value is the wrong
tool. (If a top-level export is ever truly needed for a non-scalar, serialize to a String — but that
is still a value, not a journal.)

**WHY THIS IS LOAD-BEARING BEYOND THIS CHANTIER.** This mis-fit (expected-vs-actual across a boundary)
is exactly what the planned drift/provenance specialist exists to detect — we hit it in our own
tooling. Capture it as a first-class design check, not a one-off fix. Validates
[[works-best-from-concrete-code]] (the smell only became undeniable in concrete spike code) and the
[[error-handling-layered-contract]] (catching an expected-on-success exception is the anti-pattern).

**★ CRITICAL NUANCE (user, 2026-06-14 — do NOT misread this note).** The mis-fit was the MECHANISM
(top-level `ctx.export` + union-rewrite + catch), NOT the idea of a SEPARATE STACK. Relying on another
Pulumi stack for an externally-lived concern, and referencing it from the core, is a VALUABLE system
CAPABILITY the user wants kept — do not conclude "separate stacks are bad." The work already done
crystallizes that capability: `StackCoordinate` (the handle to reference any stack), `InterventionLedger.between(from,to)`
(the time-window join between two independently-folded timelines), and the per-resource+history-fold
read mechanism. A SPIKE (commit b3ede92d, `InterventionAsRecordFactSpikeTest`) proved the aligned
mechanism end-to-end: Q1 an out-of-run inline `up()` registering only an inert `InterventionResource
extends ComponentResource` writes a readable per-resource output, touching no real infra; Q2 with NO
top-level export `up()` returns CLEANLY (no JsonSyntaxException — the catch machinery vanishes); Q3 an
intervention-only history entry folds to a benign empty Visit, not breaking `MedicalRecordReader`.
So the realigned mechanism works BOTH in-stack (dissolve into the patient record) AND cross-stack
(dedicated ledger stack, folded) — the open decision is which, see [[rke2lab:intervention-provenance-state]].
The cross-stack capability connects to the user's federated-system vision ([[rke2lab:serviceloader-specialist-spi]],
[[docrepo-dag-state]]: "each app = a repo for neighbours" is the same shape as "rely on another stack").

**★ IT BIT AGAIN, INVERTED (2026-06-14 PM, problem-oriented-provenance Task 5+6 review).** A code-quality
reviewer flagged a CRITICAL "data loss": the ledger writer named its per-resource `InterventionResource`
`"intervention-" + when.toEpochMilli() + "-" + provenance`, and the reviewer reasoned two same-millisecond
same-provenance interventions would collide on the resource URN and the second `up()` would OVERWRITE the
first → propose a MORE-UNIQUE name (content hash / UUID). This is the substrate model read BACKWARDS.
Accumulation in this system is the HISTORY FOLD, not many coexisting resources in one snapshot — the
medical-record twin proves it: `SystemdAdapterResource` uses a STABLE name (`"seed-systemd-adapter"`,
`Checkpoint.resourceName()`) across every run, yet the record accumulates many `Visit`s because each
`up()` writes a new history ENTRY and `MedicalRecordReader` folds `source.timeline()` (one Visit/entry).
So the twin-faithful fix is the OPPOSITE of the reviewer's: a STABLE resource name `"intervention"`; the
sequence lives in history, not in distinct names. A more-unique name would add spurious delete+create
churn every append for zero gain. PROVEN empirically (not argued): a 2-append test with identical instant
+ provenance yields TWO recoverable history entries (`StackHandle.history().entries()` size 2, both
`what`s fold back). **Lesson: when a reviewer proposes a fix, first read how the SUBSTRATE actually
accumulates (history-fold vs snapshot-set); a "make it more unique" instinct often fights the substrate.
Verify against the working twin + an empirical test BEFORE applying.** Validates the controller-verifies-
before-believing discipline in [[rke2lab:build-verification-gotchas]] and [[works-best-from-concrete-code]].
