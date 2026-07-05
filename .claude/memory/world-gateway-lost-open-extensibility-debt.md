---
name: world-gateway-lost-open-extensibility-debt
description: "Architecture debt: records-as-contract (shipped world-gateway) dropped the multiplexor's OPEN per-domain extensibility — a new domain now edits the central Coordinate enum + host-side OutputBuilder instead of just publishing a @Component. Revisit when a 2nd domain contributes."
metadata:
  node_type: memory
  type: project
---

**The debt (surfaced 2026-07-05 during the atlas doc cleanup, by the user's question "what did we lose
in the model by dropping the multiplexor functionality?").**

The world-gateway shipped as *records-as-contract* (wire-records + per-coordinate readers/codec +
SCHEMA_CONCORD gate). The earlier *DomainDagMultiplexor* design (never coded — no commit's `.java` ever
held it) was abandoned. Records-as-contract GAINED something the multiplexor lacked (a typed
contract-of-form: `@DocumentContract` → generated JSON Schema → build gate). But it LOST a real
architectural property the multiplexor had, and this loss was not consciously decided — it fell out of
the pivot:

* **Multiplexor (design): OPEN per-domain extensibility.** Each domain publishes its own
  `DomainDagMapper` `@Component`; a `@Reference(cardinality=MULTIPLE) List<DomainDagMapper>` aggregates
  them (the roster pattern, like `List<Specialist>`). A new domain = one `@Component`, ZERO touch to the
  center. Egress unified OSGi-side (one mux DAG→Document→Pulumi).
* **Records-as-contract (built): CLOSED central extensibility.** Verified in code 2026-07-05:
  - No `@Reference List<...>` egress roster exists (0 occurrences) — there is no open collection point.
  - `Coordinate` is a CENTRAL closed enum (`osgi/foundation/world-gateway/.../port/Coordinate.java`):
    adding a document type edits the central enum + its wire-record + its schema.
  - Egress still runs through host-side `OutputBuilder` + `toOutputMap` (part of the ~51 unmigrated
    host sites) — the very host-side hand-assembly the mux was meant to dissolve.

**Net:** a new domain now touches the CENTER (the `Coordinate` enum, the host egress builder) instead
of just contributing a `@Component` at the edge. That is a loss of the open/closed-principle property
the roster gave.

**Criticality + priority + timing (user, 2026-07-05):** when the multiplexor/roster first appeared the
user judged it a CRITICAL element of the system architecture — not a nice-to-have. It was set aside
during the records-as-contract landing; now that it is back in mind it is a PRIORITY chantier
scheduled *right after the behavior-driven pipelines are in place* (BDD-as-engine migration: socle
done, ClusterSeed / increment 2 next). Not a vague "someday": the next major chantier after the
pipelines. It lines up naturally — cluster is the 2nd contributing domain, arriving with the pipeline
migration, so the N=2 trigger and the scheduled slot coincide. The NEW design is done WHEN we get to
it (do not invent its exact shape now); preserve the existing design + direction + this criticality.

**Why it's held as debt, not fixed now:** only ONE domain (doctor) contributes today, so N=1 hides the
cost. The repay: reintroduce a DS roster (a `List<DomainDagMapper>`-style open collection) so domains
extend at the edge again. The multiplexor was RIGHT on the extensibility axis; records-as-contract was
right on the contract axis. The end state wants BOTH.

**Key correction (user, 2026-07-05) — the multiplexor is NOT dead, only its FRONTIER moves.** Inside
the OSGi world, working on RECORDS is natural (records are the domain model there); `Document` is the
currency of the FRONTIER, not of OSGi-internal work. So the roster + its `DomainDagMapper`s assemble
the egress DAG **on records, OSGi-side** (Pulumi-blind); the record→`Document` transposition happens at
ONE place — the seam edge (the egress adapter), a single crossing where the assembled DAG becomes
`@DocumentContract` `Document`s so `SCHEMA_CONCORD` still binds each coordinate. Earlier I wrongly had
the mappers emitting `Document`s directly; corrected: mappers stay record-native, the seam transposes.
The multiplexor design is preserved (do NOT delete it) as the starting point of the chantier — its
diagrams live in the world-gateway-spec §"Planned evolution — the per-domain roster".

Tracked in the world-gateway-spec §Remaining migration. See [[atlas-before-after-shift-at-merge]]
(this debt was found because the shift/cleanup was deferred, not done at merge).
