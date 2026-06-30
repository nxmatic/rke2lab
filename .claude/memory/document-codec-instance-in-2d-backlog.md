---
name: document-codec-instance-in-2d-backlog
description: In world-gateway 2D, replace the static Document.newPayload() factory with an instance DocumentCodec @Component (the JSON twin of manifests' YamlMapper) once payload construction carries config — schema awareness + deterministic field ordering.
metadata:
  type: project
---

In 2B Task 2 (feature/cluster-edge), the user flagged that three components each held their own
`new ObjectMapper()` to build gateway payloads (`DefaultReadinessAuthority`, `SystemdAdapterStage`,
`Generalist`) — a dispersed concern. Decision (Option A): the seam `gateway-port` owns payload
construction via a STATIC factory `Document.newPayload()` → `JsonNodeFactory.instance.objectNode()`
(no ObjectMapper anywhere). Static is correct NOW because the factory is stateless — uniform with the
seam's `Coordinate/Action/SymptomKind.parse()` statics, and the repo rule explicitly allows static for
factory methods.

**Why:** the user's deeper point — "prefer instances over static helpers" — is right but doesn't bind
yet: the JSON twin of manifests' `YamlMapper` (a `@Component` instance) is justified only when payload
construction carries CONFIG. `YamlMapper` is an instance because it holds custom serializers +
deterministic ordering; `newPayload()` holds nothing today.

**How to apply (2D):** when the JSON schemas land and payloads need schema-aware build/validation +
deterministic ordering, introduce `@Component DocumentCodec` in `gateway-port` (the JSON twin of
`YamlMapper`). Wire it OSGi-side via `@Reference` (DefaultReadinessAuthority) and thread it into
`Generalist` through the DAG's builder (`DoctorGraph`/`ConsultationDag.assemble` — instance-passing,
like `.access(...)`/`.driftSpecialist(...)`). The host (seed-master has ZERO `@Reference` — it is the
flat launcher, not a DS bundle) gets it via the pipeline context / service lookup, the way
`ManifestsUnitContext` threads `YamlMapper`. Delete `Document.newPayload()` and migrate its call sites
in that same change (no half-migration — uniformity rule). See [[world-gateway-2a-execution-state]]
[[world-gateway-document-design]].
