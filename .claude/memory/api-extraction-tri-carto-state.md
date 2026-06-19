---
name: api-extraction-tri-carto-state
description: "DESIGN/CARTO (read-only on integration @a100b75d, 2026-06-19): the API-extraction sort, the prerequisite to R4. The principle (user): the host must NOT see OSGi impl classes — fail-fast at BUILD, via dedicated *-api modules. But there are TWO kinds of API distinguished by WHO consumes (confirmed against integration-atlas.adoc §'two spaces'): (1) BRIDGE api — consumed by the HOST (and implemented by an OSGi bundle) → belongs to the HOST world (host owns the port, OSGi implements it; DIP, both arrows point at the host api); OSGi consumes host interface classes via system.packages.extra from the system bundle (R1's single-exporter, atlas P2). (2) INTRA-OSGi api — consumed ONLY by other bundles, never the host → stays an api bundle in the OSGi world. Sort criterion = does the host import this type? Carto found: unitrepo = pure intra-OSGi (host imports nothing); netplan = host imports only ClusterNetworkBlueprint (from the IMPL package …netplan, NOT …netplan.api — the existing split is mis-oriented vs who-consumes); manifests = 17 host-imported types, mixing real ports with impl-smelling types (likely wrong-direction crossings to invert). NOT coded, NO worktree. Naming collision (api/impl/cli across 3 spaces) still to settle."
metadata:
  node_type: memory
  type: project
---

## The element we were missing (user, 2026-06-19; confirmed in the atlas)

Two separate worlds, a SHARED knowledge of the domains (else no bridge possible), and the HOST is the
entry point (`main()` → `Pulumi.run`) so the HOST does the bridging. `integration-atlas.adoc` §"The two
spaces" already says it: *"the only edge that legitimately crosses is the bundle/host contract"*, and a
pure model reaching into host code is *"a crossing in the WRONG direction, a decomposition defect to
invert."*

So API extraction is NOT one move. There are TWO kinds of API, told apart by WHO CONSUMES:

1. **BRIDGE api** — consumed by the HOST, implemented by an OSGi bundle. Belongs to the **host world**
   (the north-bound is the host's, per our earlier decision; the bridge contract rides with it). DIP:
   the host depends on the api to consume it; the OSGi bundle depends on the api to implement it — both
   arrows point at the host-owned api, neither world depends on the other. Fail-fast at BUILD falls out:
   the host compiles against `…-api`, never the impl → a stray `new DefaultX()` in host code does not
   compile.
2. **INTRA-OSGi api** — consumed ONLY by other bundles, never the host (e.g. the unitrepo resolver, and
   `NodeEnvContributor` IF only the bundle-side registry consumes it). Stays an **api bundle in the OSGi
   world**. The user's point: "dans le monde OSGi on a aussi besoin de bundles ne portant qu'une api,
   mais cette api n'est consommée que dans le monde OSGi."

**How OSGi consumes the host's bridge interface classes (user's direct question): via the SYSTEM
classpath.** At R4 boot the host exports the bridge-api package via `system.packages.extra` from the
system bundle; the impl bundle `Import-Package`s it → host and bundle share ONE copy of the interface
(R1's single-exporter rule; atlas P2 proved this typed-access for `org.osgi.service.metatype`). No gRPC
crosses; only the pure contract package is shared.

## The sort criterion (objective)

For each type: **does any HOST module (exec/seed-master, host/*) import it?**
- YES → BRIDGE api candidate → host world (`host/<domain>-api`). BUT verify it is a genuine PORT
  (interface / exchange record), not an impl the host wrongly reaches into (= atlas wrong-direction
  defect → either promote a real port or invert the dependency).
- NO, but consumed by another bundle outside its origin → INTRA-OSGi api → OSGi api bundle.
- NO, consumed only inside its own bundle → impl, stays private.

## Carto findings (host imports, integration @a100b75d)

**unitrepo — PURE intra-OSGi.** The host imports NOTHING from `io.nxmatic.rke2lab.unitrepo.*`. Its api
(`unitrepo-handler-api`, the resolver SPI) never crosses to the host. Simplest case: it already is an
intra-OSGi api bundle; nothing to move to host.

**netplan — the existing api/impl split is MIS-ORIENTED vs who-consumes.** The host imports exactly ONE
type: `io.nxmatic.rke2lab.netplan.ClusterNetworkBlueprint` — which lives in the IMPL package
`…netplan` (NOT in `…netplan.api`). And `NetplanSynthesisService` (the SPI sitting in `…netplan.api`) is
NOT imported by the host at all — it is consumed by `netplan-cli` (exec). So netplan's current
package-level api/impl split was drawn by "interface vs class", NOT by who-consumes. The bridge type the
host actually needs (`ClusterNetworkBlueprint`) is on the wrong side. Re-sort by consumer:
`ClusterNetworkBlueprint` (+ whatever it transitively needs) is the host-facing contract;
`NetplanSynthesisService` is a CLI-facing/own-world port. (netplan→host bridge edge is the
nix-darwin-home blueprint feed — load-bearing, see flake.)

**manifests — 17 host-imported types, mixed ports + impl-smells (needs per-type triage):**
The host (exec/seed-master) imports:
- *Likely genuine bridge ports (interfaces + exchange records):* `ManifestSynthesisService`,
  `ManifestExplodeService`, `ManifestUpdateGate`, `ManifestSynthesisRequest`, `ManifestSynthesisResult`,
  `ManifestExplodeRequest`, `ManifestExplodeResult`.
- *Catalog / policy the host reads (probably bridge too):* `ManifestDomainCatalog`,
  `ManifestDomainPolicy`, `profiles.ComponentVersions`, `profiles.FloxDebugPolicy`.
- *Utility — triage:* `ManifestAnnotations`, `ManifestYaml`.
- *node.* `NodeEnvContext` (context record — likely bridge), `NodeEnvContributor` (the SPI — but it is
  ALSO consumed intra-bundle by the R3 registry; it may be BOTH a bridge port AND intra-OSGi → the api
  must sit where both can import it = host world, exported back to OSGi via system.packages.extra),
  `NodeEnvContributorRegistry` (this is an IMPL the host `new`s at IncusResourceBootstrap:1289 — a
  WRONG-DIRECTION crossing: the host reaching into a bundle impl. R3 kept `forServiceLoader()` as the
  host's dual-path; at R4 the host should consume the registry as a SERVICE, not import the class →
  candidate to INVERT, not promote).
- *units.runtime.flox.* `FloxRuntimeAssets` (an IMPL asset class the host imports — almost certainly a
  wrong-direction crossing to invert, not a port to publish).

So manifests is NOT a clean "move the interfaces" job: it is a port/impl/defect triage, and 2+ of the
host imports (`NodeEnvContributorRegistry`, `FloxRuntimeAssets`) look like decomposition defects the
atlas would flag — to be inverted at R4, not lifted into an api module.

## Open: the naming collision (still to settle WITH the user)

With `artifactId == leaf-dir-name` and "the SPACE never appears in the artifactId", one domain now wants
to exist in up to 3 spaces: host (bridge api), osgi (impl + maybe intra-osgi api), exec (cli). Without a
role suffix the three collide on `manifests`. Candidate convention (NOT yet chosen): role suffix —
`host/manifests-api` (bridge port) / `osgi/manifests/manifests-impl` (provider bundle; the recent
`manifests-core` rename then becomes `-impl`, since "core" is a misnomer once the api is out) /
`exec/manifests-cli`; intra-OSGi api bundles keep their own suffix (`unitrepo-handler-api` already does).
Decision deferred — the user was "pas encore complètement clair", and the netplan finding (api defined by
interface-vs-class, not by consumer) means the split lines move, so naming should be fixed AFTER the
per-type sort is agreed.

## Next step

- This is the design/carto output on integration @a100b75d. NEXT: agree the per-type sort for manifests
  (ports → host bridge-api; defects `NodeEnvContributorRegistry`/`FloxRuntimeAssets` → invert) and the
  netplan re-sort (`ClusterNetworkBlueprint` is the host bridge type), THEN settle the role-suffix
  naming, THEN this becomes a prerequisite slice BEFORE R4 (a dedicated worktree). R4's boot seam then
  lands on a clean api/impl frontier and the fail-fast is free.
- Touches module layout + the host/osgi boundary; the naming + invert decisions are user-owned
  ([[standing-autonomy-except-runtime-config]]). The design session does design, not impl.

See [[osgi-runtime-r4-boot-seam-state]] (R4, which this de-risks — the self-contained-jar packaging +
the boot seam), [[osgi-runtime-r3-consume-references-state]] (the dual-path + NodeEnvContributorRegistry
`forServiceLoader`), [[osgi-runtime-migration-state]] (spec §4 the runtime target),
the atlas `docs/architecture/integration-atlas.adoc` §"The two spaces" (the bundle/host contract +
wrong-direction-crossing language this builds on), [[model-substrate-alignment]].
