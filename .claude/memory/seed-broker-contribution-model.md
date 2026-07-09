---
name: seed-broker-contribution-model
description: "The seed-broker's domain-contribution model, understood on the shipped code (2026-07-09). A domain contributes to the broker by publishing a SeedHandler @Component: it already carries its LOGIC + its own encode/decode (own DocumentCodec + wire-records). What's MISSING for full edge-contribution: (1) Coordinate is a closed central enum, not domain-owned — the ONE anomaly; (2) no client-side INTROSPECTION — a client cannot discover 'what can I grow for real?'. Both to build; Coordinate first (introspection rests on it)."
metadata:
  type: project
---

**How a domain contributes to the broker (shipped, verified 2026-07-09).** A domain publishes a
`SeedHandler` `@Component` (`serves()` + `handle(Document)`); `DefaultSeedBroker` collects them by
`@Reference(MULTIPLE)` and indexes `Map<Coordinate, SeedHandler>` by `serves()`, dispatching at
`sow(wanted, seed)` via `.get(wanted)`. **Declarative Services IS the multiplexer** the June design
(`multiplexor-two-models-design`) prescribed — the interface it imports forever is `SeedHandler`. It is
SHIPPED, not missing.

**Three pieces of a contribution, and where each lives (the key finding):**

| piece | contributed by the domain? | how |
|---|---|---|
| logic | ✅ yes | `@Component implements SeedHandler`, collected by DS |
| encode/decode | ✅ yes, INSIDE the handler | the handler holds its own `DocumentCodec` (dual-realm library, neutral) and knows its own wire-records (`codec.decode(doc, ReadinessCheckpoint.class)` / `codec.encode(verdict)`). No central codec-registry to contribute — the codec is neutral machinery, each handler uses it for its own types. |
| Coordinate | ❌ NO — the one anomaly | `serves()` returns a constant of the CLOSED central enum `Coordinate`. The domain DECLARES which coordinate it serves but cannot DEFINE one — it must pre-exist at the center. |

**Why encode/decode needs no contribution.** The `Document(domain, coordinate, String)` that crosses
the seam carries an OPAQUE JSON String; the broker never decodes. `DocumentCodec` is neutral by
polymorphism — `WireEnumModule` maps ANY `WireEnum` ↔ slug reflectively (never names an enum), and
`decode(payload, X.class)` takes the type from the caller. A new domain's wire-records + seam-enums are
handled automatically. **`WireEnum` is the proof the open/contributable pattern already works in this
codebase — `Coordinate` is the lone type that did NOT follow it (closed enum instead of interface).**

**Grafting needs no domain codec either.** The scion/runbook crossing (ScenarioGraft) carries a jGiven
`ReportModel` — agnostic narration (step names + statuses), serialized by `ScenarioJsonWriter`, NOT
`DocumentCodec`. The graft engine (scenario-engine, foundation) knows zero domain vocabulary. Only the
business content riding alongside (the consultations `List<Document>`) uses the codec — and that is the
handler's own encode. So: foundation graft = domain-agnostic; domains are self-sufficient for their
Documents.

## What's missing — two builds, in dependency order

**1. Coordinate → contributable (the socle, do FIRST).** Make `Coordinate` an INTERFACE (`slug()`),
exactly as `WireEnum` already is; each domain provides its own enum (`DoctorCoordinate implements
Coordinate { READINESS_VERDICT("readiness-verdict"), ... }`). Then a handler's `serves()` returns a
domain-owned coordinate, and "publish a handler = publish its coordinates" holds end to end. Nothing
else needs to move: the broker indexes by identity (works), `WireEnumModule` handles it by
polymorphism (works). Verified NO prod code needs the closed set — `Coordinate.values()`/`valueOf`/
exhaustive `switch` = ZERO; the only `Coordinate.parse(slug)` caller is a test (`BrokerVocabularyTest`).
So opening the enum breaks no dispatch logic. ATOMIC change (user: no two-versions-of-the-system), but
bounded — today only ONE domain exists (`Domain.DOCTOR`); all 6 coordinates + wire-records are doctor's.
The wire-record binding `@DocumentContract(Coordinate)` and the `SCHEMA_CONCORD` ASM gate must follow
(the gate scans coordinate↔wire-record; if coordinates become domain enums, the gate scans per domain).

**2. Client-side INTROSPECTION (rests on #1, design AFTER).** User's insight (2026-07-09): the client
side is missing its half — "how do I discover what I can grow FOR REAL?", the REST-introspection /
OSGi-service-discovery analogue. A client today must hardcode `Coordinate.X` and know its wire-record.
The DESCRIPTION already exists but is BUILD-TIME only: `@DocumentContract` makes the wire-record BE the
schema (components → JSON Schema, projected by `SCHEMA_CONCORD`). It is consumed by the gate, never
offered to a client at runtime. The cure, symmetric to the domain side: the broker offers `available()`
→ the set of coordinates ACTUALLY served (the published handlers — "for real" = who has a grower, not
the theoretical enum) + each one's projected schema. This is the demux of the DS roster: the client
asks the broker "what can you grow?", the broker answers from what its handlers declare. The REST index
+ per-resource schema, in-JVM. Open question deferred to design time: does the handler carry its own
description (coordinate + wire-record/schema), or does the broker derive it from the roster
(serves() + reflect the @DocumentContract record)? Decide when building #2, on #1's stabilized shape.

See [[gateway-is-rest-in-jvm-insight]] [[multiplexor-two-models-design]]
[[world-gateway-lost-open-extensibility-debt]] (this is that debt's concrete resolution path).
