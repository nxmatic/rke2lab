---
name: doctor-remediation-model
description: "Doctor model extended by the 2026-06-07 subagent brainstorm — Remediator tier, next-visit loop closure, recruit-a-specialist gradient. Enriched wip/spec.adoc (Prescription + The Doctor + AI-seam sections); not yet implemented."
metadata: 
  node_type: memory
  type: project
  originSessionId: 6d3faadb-1da7-486c-9310-99b6dd4c49b5
---

A 2026-06-07 brainstorm (trigger: the user noticed Claude's *subagent* feature mirrors the
doctor's AI-ready `Specialist` seam) extended the medical model with three LOCKED decisions,
written into `wip/spec.adoc` (sections `[#the-remediator]`, `[#loop-closure]`,
`[#recruiting-a-specialist]`, anchor `[#ai-seam]`). DESIGN ONLY — no code yet. Builds on
[[runbook-doctor-state]]; the doctor core already exists (Generalist + DbusTcpSpecialist).

**1. Remediator tier (NEW, enters the model — user: "c'est important").** The doctor stays
PURE (reads/writes the record, never touches the live system). A distinct role — the
*remediator* — *administers* the treatment, confining ALL live-system risk to one tier. The
`Prescription.programRef` it already carries is the referral address: routes a prescription
to a remediator exactly as `Generalist` routes a symptom to a specialist (mirror image — the
routing key already exists, dormant). Remediator KINDS = real ops shapes: *nurse* (imperative
one-shot, e.g. RESTART_UNIT, today's only case), *pharmacist* (dispenses: apply manifest /
fill config input — idempotent desired-state change), *physiotherapist* (progressive/iterative:
retry-backoff, gradual rollout). Structural axis the plan must respect: **declarative
remediation → the Pulumi ENGINE ITSELF is the remediator** (operator changes desired state,
next `up` converges, no separate executor); **imperative remediation → needs an explicit
remediator** (no "desired restarted state" to converge to). `programRef` carries the gesture's
nature (declarative/imperative).

**2. Loop closure = the NEXT VISIT, on the operator's terms (user's call).** Never administer
mid-flight during a `pulumi up`. Loop closes BETWEEN visits via TWO VOICES: the *medical
record* (doctor's voice — prescription persists in stack state, layer 3) PROPOSES; the *stack
config* `Pulumi.<stack>.yaml` (operator's voice = the visit's admission context) DISPOSES; the
*next `up`* is the check-up that administers what the operator authorized AND re-observes.
Lands exactly on the locked "re-observe = the program re-running, not a provider Read". The
CLAUDE.md "propose, don't execute" line holds STRUCTURALLY with no amendment: doctor proposes
(record) / operator disposes (config) / engine executes (converges). Unattended CI ≠ autonomous
— it converges on version-controlled, reviewable config. Live-system risk becomes a question
of config content, not agent trust.

**3. Recruit-a-specialist (the meta move — user: "et si le généraliste ne sait pas… la
prescription est de recruter un nouveau spécialiste").** The Generalist's two dead ends — *no
route* for the symptom, and *routed but none prescribed* — stop being termini and become a
*recruitment prescription*. The two gaps recruit differently: no-route → a NEW specialty (new
domain); routed-but-empty → a DEEPER specialist in that domain. The recruiter is a
META-SPECIALIST whose domain is the clinic itself. **Honored via a gradient ad-hoc→codified
(chosen):** a generic Claude-backed `Specialist` is the universal fallback — treats the unknown
case ad hoc immediately AND emits a recruitment prescription; if the frontier symptom recurs,
the operator CODIFIES it into a deterministic `Specialist` (DbusTcpSpecialist = the codified
end; Claude-backed = the starting end). Routing an unknown symptom to a Specialist instantiated
for it IS a subagent dispatch (fresh focused agent, dossier as its only context). **This is the
concrete reason T3 (open roster + contributed routing) stops being speculative** — a `switch`
over a closed enum cannot recruit. **DOGFOODING (user: "on fait du dogfooding à notre propos"):**
"codify a recruited specialist" = the design loop itself — operator + Claude re-enter
brainstorm→design, triggered by a REAL remediation exercise (healing the live
`systemd-adapter degraded` on master, the dbus-tcp:12434 symptom in [[master-provisioning-state]]).
The runbook's recruitment prescriptions = a self-generated backlog of specialists to build.

**Two seams, not one (clarified in `[#ai-seam]`):** diagnosis seam = READ-ONLY (Specialist
correlates+prescribes, never acts — like a read-only subagent); remediation seam = where the
ACTION SURFACE lives (MCP is the candidate, still deliberately undesigned). The `programRef`
catalog IS the *therapeutic formulary* — the bounded set of authorized gestures an MCP surface
would expose as tools (never arbitrary shell). Also corrected a mis-cadrage: a Specialist is
NOT a "reduced" agent — it's a DEEPER lens (correlates what escaped the generalist, can even
correlate across patients/stacks), as complete a view as the generalist; statelessness holds
because the record is READ (passed in / edge-propagated per T1), never HELD.
