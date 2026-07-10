---
name: seed-broker-host-adaptation
description: "How the HOST modules adapt to the migrated seed-broker model (design settled 2026-07-10, code NOT yet written). OSGi is migrated (SeedEnvelope/SeedCoordinate/SeedCodec/@Graft/DoctorGraftReflector shipped); the host lags. Getting a SeedBroker host-side is SOLVED (awaitService / FrameworkLaunchPipeline.during). The DSL is ONE verb sow — HATEOAS: a plant advertises the coordinates you sow next. The Pulumi stack is an envelope store; a stored envelope re-sows; CRUD addresses the SHELL (coordinate), never the payload. Three stale pulumi-edge sites + SystemdAdapterResource still open envelopes."
metadata:
  type: project
---

**Context.** The OSGi world is migrated onto the seed-broker model; the HOST modules (aggregator
`host/` → `host-parent` + `pulumi`; the code module is `pulumi-edge`) are NOT. This memory is the
host-side design settled in the brainstorm of 2026-07-10 (whiteboard was `.claude/claude-preview.adoc`).
Sister memory: [[seed-broker-contribution-model]] (the domain/OSGi side + the REST analogy).

## THE GOVERNING CRITERION + shape (user, 2026-07-10) — read this first

**The shape wanted: `pulumi ↔ seed ↔ doctor`.** The MIDDLE (`seed`) speaks GARDENING — a neutral
vocabulary knowing neither side. Two MIRROR adapters map onto it, and the direction is the point: each
SIDE projects ONTO the neutral middle; the middle projects onto no one. The EDGE (`pulumi-edge`, host)
projects Pulumi's model; the HANDLER (`doctor-core`, OSGi) projects the doctor's aggregate. **The two
adapters never share a word** — that IS the decorrelation, stated structurally (Pulumi cannot transpire
into the doctor because no edge exists where their words touch).

**The criterion: the host modules speak ONLY seed; ALL logic that speaks doctor migrates into the doctor
domain.** Binary test per site: *does this host code name a doctor type/field?* yes → move OSGi-side; only
`SeedEnvelope`/`SeedCoordinate`/a parcel → stays, legitimate transport. (This is why `MedicalRecordDump`'s
YAML rendering MIGRATES: knowing a record has "reports" IS doctor logic, so render-as-YAML becomes a
coordinate one sows; the host keeps only "open Felix, sow, write".)

**The neutral CRUD port lives in `seed-broker-port` (the middle), NOT doctor-port (decided 2026-07-10).**
A port named `MedicalRecordJournal` means the frontier knows the doctor — the leak moved from payload to
interface NAME. So the port speaks gardening only (sow an envelope into a parcel, harvest a parcel's
envelopes, addressed by `SeedCoordinate`/a parcel, never `Patient`). Nuance vs the port-ownership law
("the port is the consumer's"): the consumer is still the doctor, but the FACE is neutral by requirement,
so it is homed with the vocabulary it speaks (the middle). The doctor's `historyOf(Patient)` view sits
BEHIND it, as its projection. The host EDGE provides it; the doctor CONSUMES it.

**The vocabulary in LAYERS (a trap the user caught: "le terrain c'est le patient?" — NO).** `terrain` =
the whole provisioned ground (neutral); a PARCEL = one stack = `StackCoordinate(project, stack)` (neutral,
ALREADY exists in `pulumi-edge`); `Patient(org, project, stack)` = the DOCTOR's projection of a stack. A
stack update = a stack state (neutral) / `Visit` (doctor). A `SeedEnvelope` under a coordinate = a plant
(neutral) / `ConsultationReport` (doctor). The frontier addresses by the NEUTRAL parcel, never `Patient`.

**Sequencing — OSGi FIRST, host SECOND.** Fix `seed ↔ doctor` in OSGi first (the doctor is the consumer,
furthest along — graft path ships, ports already speak `SeedEnvelope`; remaining = neutralise the port
FACE + wire `Patient → parcel`), THEN `seed ↔ pulumi` in the host (the edge is furthest behind — 3 sites
don't compile against the renamed seam). Not preference: the neutral middle port must be FROZEN before
either projection is written, else the edge migrates against a moving target.

**The actor model (settled 2026-07-10 — CORRECTS an earlier "the broker is not a gardener").** The
gardener is not who OWNS a plot; it is the transversal KNOW-HOW that grows correctly on ANY plot — so the
broker IS the gardener. Roles: the COMMISSIONER / provisioner = Pulumi via the edge (the real actor:
wants, `pulumi up`, and CONSERVES the harvest at home); the GARDENER = the `SeedBroker` (`DefaultSeedBroker`
routing handlers by coordinate — owns no plot, knows how to grow on each); the SOILS/plots = the DOMAINS
(doctor, cluster); the TOOLS + channel = the host (`pulumi-edge`, seed-master). "The client sows" is
sharpened to "the commissioner asks the gardener to sow" (REST: the client requests, the server acts).
Conservation: the commissioner keeps the reaped harvest in a container whose choice FOLLOWS the harvest's
NATURE — pot = per-resource output, vase = top-level export, fridge/cellar = stack history (not a free
choice; the code already places three ways by nature). Full register: `docs/architecture/osgi/seed-gardening-lexicon.adoc`.

**What is SHIPPED OSGi-side (so the host can rely on it).** `SeedEnvelope(domain, coordinate, payload)`;
`SeedCoordinate` interface; `SeedCodec`; `@Graft` marks a wire-record component; `DoctorGraftReflector`
(`@Component implements SeedHandler`) serves `GraftCoordinate("doctor")` and reflects the `@Graft`
components of `Consultation`. The doctor READ/WRITE ports (`MedicalRecordJournal`, `InterventionJournal`,
`InterventionLedgerWriter`) ALREADY speak `SeedEnvelope` opaque. Only the host IMPLS lag.

## Getting a SeedBroker host-side is SOLVED (verified 2026-07-10)

Not the migration's hard part. `DefaultSeedBroker` is `@Component(service = SeedBroker.class)` — a
published OSGi service. Two host contexts already resolve it:

+ *in a pipeline stage* (Felix already open): `connection.awaitService(SeedBroker.class, 5000)` — the
  mechanism is `OsgiConnection.awaitService` (a `ServiceTracker` on the `BundleContext`). `SystemdAdapterStage`
  already does `broker.sow(READINESS_VERDICT, checkpointDocument())`.
+ *in an offline CLI* (no framework): `FrameworkLaunchPipeline.embedded().during("...", SeedBroker.class,
  broker -> …)` opens Felix, resolves, closes. `RecordInterventionCommand.main` already does this.

So "who hands the broker to the host" was the wrong question — resolution ships. The migration is the
sites still speaking the pre-rename vocabulary and still OPENING envelopes.

## The DSL grammar — ONE verb, HATEOAS (user was learning it; a correction landed)

The spec's REST table listed TWO discovery idioms; only `OPTIONS` (ask a meta-coordinate for a shape) had
been developed. The host need — *how do I know the coordinate to sow next?* — is answered by the OTHER
idiom, HATEOAS: **a reaped PLANT advertises the coordinates it can be sown at next.** Grammar, ONE door:

+ `sow(coordinate, seed)` → reap a `plant` PLUS the coordinates of the grafts realisable with it.
+ `sow(graftCoordinate, plant)` → realise the chosen graft. The SAME verb at an advertised coordinate.

**CORRECTION (user was learning the DSL, proposed a second `graft(...)` verb — I stopped it):** there is
NO second verb. Following a link is a `sow`, exactly as REST HATEOAS follows links with the same GET/POST,
never a new HTTP method per resource. The spec invariant is "one door, self-describing — NO new interface
method". The advertised coordinates are slugs (Strings): the host reads them, never a class.

## The storage model — the stack is an envelope store; CRUD addresses the SHELL

**The stack is a store of sealed envelopes; a stored envelope RE-SOWS.** `sow → plant → (stored in the
Pulumi stack) → seed` — the horticultural cycle closed. The Pulumi model already maps 1:1 onto doctor's
aggregate (stack=Patient, update=Visit, resource=ConsultationReport, output=an aggregated sub-tree — from
the spec), so no new common model to invent.

**CRUD touches only the SHELL, never the payload.** `SeedEnvelope(domain, coordinate | payload)`: the
shell (`domain`, `coordinate` — neutral Strings) is what the frontier files/finds BY; the payload is
sealed, never read. The frontier ASKS the broker for the slot NAMES (Strings), never their shape (asking
the shape would rebuild a doctor type host-side — the leak). The four ops:

+ CREATE — file one envelope under its coordinate slug, payload verbatim (no FIELD_ split).
+ READ — collect by coordinate slug: `outputsNamed("consultationReport")` becomes
  `outputsNamed(<coordinate-slug>)`; cross-resource collection (one element per rootstock) unchanged,
  returns a list of opaque envelopes.
+ UPDATE — a new `up()` appends a history entry under the stable resource name; the timeline IS the log.
+ DELETE — n/a; both stores are append-only.

**The one mechanical change:** `StackSnapshot.outputsNamed(key)` stops being keyed on a domain FIELD and
starts being keyed on a COORDINATE slug. That single reshape kills both leak names —
`SeedBrokerCatalog.FIELD_*` (foundation) and the `OUTPUT_KEY` constants. Store the sealed String, return
the sealed String, the slot name comes from the plant.

## The concrete host sites (the worklist)

Three `pulumi-edge` files are STALE against the renamed seam (import `Document`/`Coordinate`/`Domain`/
`SeedBrokerCatalog`/`VisitWire`/`DocumentCodec` — none exported anymore → module does not compile against
the migrated seam):

+ `StackMedicalRecordJournal.visitDocument` — builds a `VisitWire` from `outputsNamed(FIELD_*)`. Target:
  read stored envelopes back as opaque SEEDS, transport to OSGi (doctor re-sows to reconstruct). No
  `VisitWire` built host-side.
+ `StackInterventionJournal` — builds a `Document` from `outputsNamed(OUTPUT_KEY)`. Same opaque-transport
  target.
+ `PulumiInterventionLedgerWriter.append(Document)` — signature must become `append(SeedEnvelope)` (the
  port already declares it).

Plus the WRITE frontier in seed-master:

+ `SystemdAdapterResource.copyDiagnosticOutputs` (and `ClusterReadinessResource`) — decodes
  `Consultation.class` and routes `.consultationReport()`/`.expectations()` to two named keys. Target:
  file the graft coordinates the plant advertises, each under its own coordinate — never decode.

Plus the offline dump:

+ `MedicalRecordDump.main` — decodes `VisitWire`, renders YAML host-pure. RESOLVED (user, 2026-07-10):
  opening Felix for a dump is fine → go uniform with `RecordInterventionCommand`, render-as-YAML becomes a
  `sow`. No host-pure transcoder exception.

**Two natures of the work, do not conflate:** (1) mechanical catch-up (`Document→SeedEnvelope` etc.) that
merely unblocks compilation but KEEPS the host opening envelopes — leaves the opacity debt; (2) the true
Pulumi-frontier decorrelation (ask the broker, CRUD-by-shell, delete `SeedBrokerCatalog`+`OUTPUT_KEY`).
Nature 2 is the alignment; nature 1 alone is not enough.

## Settled decisions (2026-07-10 — no vocabulary collision; it was a coherent register all along)

The `@Graft`-vs-"graft" worry DISSOLVED: `scion`/`rootstock` are used with the SAME meaning on both
channels (data + observability), so graft/scion/rootstock are ONE horticultural register, `graft` its
single verb. What changed is the annotations name ROLES, not the verb:

+ *`@Graft` → `@Scion`* — an annotation names what a component IS (the scion, a filed sub-tree), not a
  verb (like `@Column` not `@Persist`).
+ *`@Rootstock` (NEW)* — its twin: marks the RECEIVER's IDENTITY component (`scenarioId`, the join key).
  The broker reflects BOTH and returns `{rootstock → [scions]}`; the frontier hardcodes NOTHING — not a
  field (`@Scion` gives it), not a resource (`@Rootstock` gives it). Closes the last hardcoded name.
+ *`GraftCoordinate` → `SplitCoordinate`* — the meta-verb's role is "split the envelope" (return scions
  grouped under rootstocks), not "give me the grafts".
+ *The relation crosses ASYMMETRICALLY:* WRITE needs it (a Pulumi output is nested under a resource, so
  the split returns `{rootstock → [scions]}`); READ does not (flat by coordinate, the domain re-associates
  by the rootstock value OSGi-side).
+ *`végétal` = the GENUS* (the sealed envelope; the stage — seed/plant/cutting/fruit — is unknowable
  without opening). The TYPE is NOT renamed: `SeedEnvelope` stays in code (it carries "sealed envelope",
  the opacity signal); `végétal` lives in the lexicon, not the class name.
+ *`bouture`/cutting = a FOLLOWABLE coordinate* (HATEOAS), not the result of following it.
+ *Offline `MedicalRecordDump` resolves the broker BY CAPABILITY* (uniform), no host-only `new` shortcut.

See [[seed-broker-contribution-model]] [[diagram-preview-file]] [[docs-diagrams-not-java]]. Lexicon:
`docs/architecture/osgi/seed-gardening-lexicon.adoc`; spec: `docs/architecture/osgi/seed-broker-spec.adoc`.
