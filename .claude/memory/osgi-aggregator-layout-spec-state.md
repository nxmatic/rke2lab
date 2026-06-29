---
name: osgi-aggregator-layout-spec-state
description: Out-of-band SPEC (2026-06-28) for re-laying-out the osgi/ aggregator into 3 groups by nature (foundation/runtime/domains). NOT built — scheduled for a DEDICATED branch AFTER feature/cluster-edge merges (it renames exchange→world-gateway, which 2B touches). Spec + prompt committed on feature/cluster-edge to preserve the work; implementation is its own later increment.
metadata:
  type: project
---

A design produced out-of-band (2026-06-28) while this branch worked world-exchange 2B. It is
**descriptive only** — it mutates no pom. Committed on `feature/cluster-edge` to preserve it; the
implementation (the prompt's §B) is **scheduled for a dedicated branch AFTER this branch merges** —
it renames `exchange`→`world-gateway`, precisely the module 2B edits, so running it before the merge
would chase a moving target.

**The target layout** — stop `osgi/` mixing 6 natures in one flat `<modules>`. Three intermediate
groups + 2 flat Maven parents:

- `foundation/` (compile-time shared): domain-annotations, world-gateway (was exchange-port), pipeline.
- `runtime/` (boot/exercise): boot, runtime-host (was runtime — name collision), junit-testkit, bench.
- `domains/` (métier): doctor, manifests, systemd, netplan, cluster, unitrepo; `ssh-to-age-edge` stays
  a flat leaf under `domains/`.

**Rules:** a testkit lives WITH the bundle it tests (`testing/` is NOT a nature); an aggregator exists
iff ≥2 modules (singletons reduced — world-gateway, ssh-to-age-edge stay leaves).

**3 renames (the surgical part):** `exchange`→`world-gateway` (the door to the OSGi world; survives the
embedded→remote RSA evolution; `-port` suffix drops from the MODULE name but the bundle stays
`type=seam` and its package becomes `io.nxmatic.rke2lab.world.gateway.port`; `ExchangeCatalog`→
`WorldGatewayCatalog`); `runtime`→`runtime-host`; `jgiven` dissolves INTO `pipeline`
(pipeline absorbs the jgiven-wrap carrier, anti-split bnd surgery — `pipeline-testkit`/`pipeline-probe`).

Spec: `docs/architecture/osgi/osgi-aggregator-layout-spec.adoc` (12+ C4/Mermaid figures, §5 sub-decisions,
§6 migration mechanics). Prompt (§A integrate / §B implement): `…-spec.prompt`. Absorbs the
[[jgiven-domain-into-pipeline-debt]] and the unitrepo-embed backlog (the layout tranches them). See
[[layout-skeleton-state]] [[world-exchange-document-design]] [[cdk8s-carrier-flat-jar-pattern]]
[[federated-unitrepo-p2p-design]].
