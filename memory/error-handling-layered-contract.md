---
name: error-handling-layered-contract
description: "Layered error-handling contract for the pulumi-automation-ext + medical-record code: Optional=nothing-here, exception=went-wrong; leaves throw, aggregator carries partial result + suppressed, caller decides"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Error-handling contract the user DECIDED for the medical-record / pulumi-automation-ext code
(2026-06-08), and the principle behind it: **each layer has its own responsibility — a subordinate
class does NOT decide the error policy; only the caller knows how to treat an error.**

**The rule — two disjoint return channels, never conflated:**
- `Optional<T>` = "legitimately NOTHING HERE" (no history yet, empty stack). It must NEVER hide an error.
- a thrown exception = "something WENT WRONG" (file present-but-unreadable, reshape bug, fromJson
  failed, our own NPE).

**The strategy menu (user's generalization, 2026-06-08) — each layer picks per what its level knows:**
1. *let it pass* — propagate as-is (the layer has nothing to add);
2. *catch + recover* — the layer has the context to substitute/repair and continue;
3. *rethrow enriching* — propagate but add information (e.g. StackCheckpoint adds `file()`);
4. *fail-fast* — stop at the first error;
5. *fail-at-end* — accumulate, finish the possible work, then raise the tally (the aggregator's
   partial + addSuppressed).
6. *route to a remediator (transverse)* — hand the error off to a remediation handler instead of
   deciding inline. The user noted (2026-06-08) this lands squarely back in the MEDICAL MODEL —
   *dogfooding*: an error IS a symptom, handling-it-at-the-right-level IS the consultation, calling a
   remediator IS the prescription ([[rke2lab:doctor-remediation-model]]). The layered error contract is itself
   an INSTANCE of the doctor model, applied to our own execution flow (the "patient" = the running
   pipeline) rather than to provisioning. Conceptual bridge worth keeping; not in scope to implement now.
This is a COMPOSITION VOCABULARY, not one rule: StackCheckpoint = rethrow-enriching;
MedicalRecordReader = fail-at-end; MedicalRecordDump = catch+recover (dumps the partial). The single
invariant ABOVE the menu: never downgrade to a silent log — always one of these explicit strategies.

**THE PAYOFF (user, 2026-06-08):** if every level COLLECTS its error context and ENRICHES (rather than
flattening to a log line), there is almost always someone above who knows what to do — and if it's the
human at the very end of the chain, the *accumulated context is enough to know* without reproducing the
bug. This inverts the log anti-pattern: today an error → a decharné log line → you must RE-FABRICATE the
context (replay, instrument, guess state); with this contract the exception CARRIES its context, so you
READ the cause instead of RECONSTRUCTING it. Diagnostic cost drops from "reproduce" to "read".

**Design corollary (honour it in the remaining tasks — enrichment must add IDENTITY, not just re-wrap):**
each layer adds the identifying fact only it holds. StackCheckpoint adds `file()` (done). The aggregator
(Task 11) must make each `addSuppressed` say WHICH `StackHistory.Entry` (version + when), not attach the
raw StackCheckpointException blind. The dump (Task 13) message must carry the Patient (org/project/stack).
Goal: at the chain's end the human reads "patient X, visit v7 of Jun-6, checkpoint …/sandbox-200.json,
cause = truncated JSON" — full diagnosis, zero reproduction. A re-wrap that adds no new identity is noise.

**Dogfooding the collaboration itself (user, 2026-06-08):** the same payoff makes debugging *with Claude*
faster. Today they feed me the END-OF-CHAIN error WITHOUT context, and I must help RECONSTRUCT the
upstream chain reaction — which costs enormous time. With context travelling WITH the error, that
reconstruction vanishes: the user hands me the enriched context and my help shifts from "understand what
happened in the system" (slow) to "how to remediate" (fast, targeted). The decharné end-of-chain error
is exactly what we get "fed without context" — and this design kills it. So enrichment effort is not
gold-plating: it directly buys back the time we currently burn understanding incidents together.

**CHECKED, not unchecked (user reversal 2026-06-08 — supersedes the earlier "unchecked for clean
chains" choice).** Premise corrected: in our model unchecked did NOT mean "nobody handles it" — we'd
only chosen it to avoid `throws` on every intermediate method. The user replaced that shortcut with the
*principled* Java rule (Effective Java): RECOVERABLE condition ⟹ CHECKED (force the caller to decide);
programming bug ⟹ unchecked. The deciding insight is medical: *the medical record is an ENRICHMENT of
the diagnosis, not a PRECONDITION* — "a practitioner without access to the patient's record can still
diagnose, less informed, and prescribe at lower quality." So a record-read failure (a corrupt
checkpoint, or the whole history dir unreadable) is RECOVERABLE: fall back to the present complaint
(`currentSnapshot`/`exportStack`, which is independent of the history files). Therefore the WHOLE family
is CHECKED (`StackCheckpointException`, `StackHistoryException`, `MedicalRecordReconstructionException`
all `extends Exception`), applied uniformly (CLAUDE.md uniformity). Bonus: the compiler then FORCES the
aggregator to catch the leaf exceptions in its loop → fail-at-end (partial record) becomes structurally
guaranteed, not optional. Friction (intermediate `throws` on `StackHandle.snapshotOf`,
`SnapshotSource.at`, `MedicalRecordReader.read`) is the FEATURE. The directory-unreadable case is a
checked `StackHistoryException` carrying the DIR path (NOT `UncheckedIOException`).

**WHERE a degraded-diagnosis caveat gets recorded (treats()-topic decision, PARKED 2026-06-08).** When
the record is inaccessible and the practitioner diagnoses anyway (degraded), the "I worked without the
chart, lower confidence" caveat is recorded *doctor-to-doctor, NEVER to the patient*. User's rule (and
Claude conceded, dropping its "put it on the Prescription" lean): the `Prescription` is addressed to the
PATIENT (it's the treatment); a confidence caveat is a meta-fact about the diagnostic process → it
belongs in the doctor-to-doctor `ReferralReply` (the courrier), not the prescription — putting it on the
prescription is a category error. Sharpening (Claude's added nuance, accepted): the exact carrier is the
doctor-to-doctor channel *at the level where the degradation occurred* — a SPECIALIST that loses record
access notes it in ITS `ReferralReply`; if the GENERALIST loses the whole record before composing
referrals, the caveat lives one level up, on the CONSULTATION/visit (still doctor-side). This carrier
choice is DEFERRED to the treats() topic (ReferralReply does not exist yet); the additive-record
guarantee means we need not fix it now. What it decides for TODAY: it confirms CHECKED (a record-read
failure is recoverable → must be caught → noted), regardless of final carrier.

**SEVERITY IS THE CALLER'S CONCERN, NOT THE LEAF'S (user practical insight 2026-06-08 — the clinching
argument for checked).** Two "no access" cases differ in gravity: losing the RECORD (history / past
checkpoints) = degraded-but-workable (diagnose on the present complaint); losing the CURRENT STACK
(`exportStack`/present state unreadable) = CRITICAL — in production the generalist's only prescription is
"page the human TASK FORCE" (escalation = the TOP of the remediation gradient, the upward cousin of
recruit-a-specialist). The decisive twist: *the same leaf failure has different severity depending on the
caller* — can't-read-stack is "report unavailable, warn operator" for the OFFLINE DUMP (in scope) but
"production down, escalate engineers" for the LIVE in-run doctor (treats(), out of scope). The leaf
CANNOT know which, so it must NOT decide severity (no log, no swallow, no built-in "critical"); it throws
CHECKED and EACH caller assigns severity. A leaf that judged severity would be wrong half the time. The
task-force escalation tier + severity-differentiation are doctor/treats()-scope insights
([[rke2lab:doctor-remediation-model]]), NOT code for the current offline-dump tasks — the dump just fails its own
operation with context.

**THE FINAL EXCEPTION HIERARCHY (decided 2026-06-08):**
```
StackException (abstract, extends Exception, carries Path path())   # common checked root
  ├─ StackAccessException    # I/O: file absent / unreadable / permission  → RETRYABLE
  └─ StackContentException   # invalid content: bad JSON, missing latest, fromJson failed → NEVER retry
```
TWO levels, not three — the speculative intermediate `StackReadException` was dropped (YAGNI: in this
module everything IS a read; add a mid-layer only if another category appears). Both
`StackCheckpoint.snapshot()` AND `StackHistory.entries()` declare `throws StackAccessException,
StackContentException` (CHECKED). `StackCheckpointException` and `StackHistoryException` are DELETED.

*Two orthogonal axes — the source of an earlier confusion, now resolved:* (axis 1) retryable-vs-not →
splits Access from Content; (axis 2) checked-vs-unchecked → recoverable⟹checked. The user first said
"two independent roots", but that was a CONSEQUENCE of a hypothetical checked+unchecked split (you can't
share a base across `Exception`/`RuntimeException`). Since BOTH ended up checked, that constraint FALLS
and the common root `StackException` is restored. "Non-retryable" ≠ "unchecked": a corrupt checkpoint is
not retryable but IS recoverable (the aggregator skips that visit, keeps the rest, notes it in
suppressed) → checked. Both checked ⟹ compiler forces the aggregator's fail-at-end.

Rules that produced this: (1) *type = the caller's DECISION level* — here retry-vs-never, so exactly TWO
leaf types, NOT one-per-cause and NOT one-per-subsystem; (2) the SUBSYSTEM that failed (history vs
checkpoint) is NOT a type — the caller re-qualifies via `path()` / `getCause()` / `getSuppressed()` if it
cares ("l'appelant peut toujours re-qualifier en regardant les suppressed"); (3) the thrower picks the
type that most precisely matches the error it hit and carries the info up; a method with several distinct
error cases lists several types in `throws`, one per case; (4) "one exception per domain" = put the
specific cause(s) in cause/addSuppressed, don't multiply types. Caller usage: `catch
(StackAccessException)`→retry; `catch (StackContentException)`→note/never-retry; `catch
(StackException)`→any module read failure (family); then `getCause()`/`getSuppressed()` for the nuance.

**Propagation across layers:**
- *Leaf* (e.g. `StackCheckpoint.snapshot()`): does NOT swallow, does NOT log, does NOT return
  empty-on-error. It THROWS a typed CHECKED exception carrying context (the path + cause). It returns a
  value (not Optional) — you only build a leaf for a resource you expect to be readable.
- *Aggregator* (e.g. `MedicalRecordReader.read(patient)`): reads N leaves, builds the PARTIAL result,
  collects per-item failures; at the end, if any failed, it THROWS one exception that (a) REFERENCES
  the partial return value via an accessor (e.g. `partialRecord()`), and (b) carries each leaf failure
  via `Throwable.addSuppressed(...)`.
- *Caller* (MedicalRecordDump / sandbox / future in-run doctor): DECIDES the policy — strict (let it
  throw) or lenient (catch, read `partialRecord()`, walk `getSuppressed()`).

**Why this shape (vs the rejected ones):** a silent `Optional.empty()` at the leaf was wrong — it
conflated absent-normal / corrupt / our-own-bug, and let the subordinate usurp a decision that is the
caller's. A log-and-degrade was also rejected (still the subordinate deciding). A `Result`/record
carrying value+errors at every return was rejected because it forces a check at EVERY leaf return
("pfouh"); the partial+suppressed-at-the-aggregator gives ONE inspection point instead. A
caller-supplied lambda strategy is a valid alternative KEPT IN RESERVE for the out-of-scope in-run
doctor (invert the view: caller provides the error strategy) — not needed for the single in-scope
consumer (the YAML dump).

**THE DEEP WHY (user's stated principle, 2026-06-08 — this is the rationale, cite it against any
"just log it" reviewer):** the user fights daily against systems where defects are detected *mainly via
logs*. When an error becomes a log line, the error-HANDLING logic is deported — out of the code, into
other systems (monitoring, alerting), and eventually onto the human, who must correlate logs after the
fact. Yet had the error been handled *at the right level* — the layer that actually has the context to
decide — it was usually trivial to resolve. So: an error must stay a CONTROL VALUE that propagates to
the layer that knows what to do, NOT be downgraded into a log signal that offloads the decision. A log
is a TRACE, never the DETECTION mechanism. This is precisely why the rejected `log.warn +
Optional.empty()` design was wrong: it converted an error into a log line and deported the decision.

**Supersedes** the plan's earlier "degrade to empty / never throws" wording in Tasks 3, 4, 5, 11, 12.
Optional survives ONLY for genuine emptiness (`latest()`, `currentSnapshot()`, empty `timeline()`).
Relates to [[rke2lab:medical-record-query-api-state]]; consistent with the project's anti-silent-failure stance
([[review-scope-backlog]] and the clusterApi silent-failure lesson in CLAUDE.md).
