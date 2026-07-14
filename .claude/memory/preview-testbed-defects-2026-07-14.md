---
name: preview-testbed-defects-2026-07-14
description: "The first cluster-seed pulumi preview run as a testbed — 4 packaging/gate defects fixed + the I6 host-tree architectural correction (host-entry CRUD moves OSGi-side)."
metadata:
  node_type: memory
  type: project
---

## Playing the first preview was a TESTBED — it walked the whole host boot and exposed a defect chain

Running `pulumi preview --stack dev` on the seed-master exec-jar (2026-07-14) is the first time the
full path runs: `com.pulumi` boot → flat JUnit launcher (BDD-as-engine, host-side, outside Felix) →
`ClusterSeedScenario` GIVEN → the WHENs. Each failure peeled to the next; the run is a diagnostic
harness, not a throwaway. Six defects, in the order the preview surfaced them:

**D1 — gson staged bundle-only (FIXED, commit eeebd0efe).** `com.pulumi` NoClassDef'd on
`com.google.gson.Gson` at `Pulumi.run`. Regression since bbox-edge (`1c37ea7ac`): gson entered
`stagedGas()` (bbox client pulls it) AND became a direct compile dep, but `isRealmLibrary` only kept a
third-party bundle flat when a domain IMPORTS its package — bbox-edge does not import gson. Fix:
`ResolvedBundle.directlyDeclared` (grafted from the Aether graph's root children), and a
directly-declared third-party bundle is a realm library (flat + staged), guarded by the boot-stack
exclusion. See [[gson-flat-vs-staged-regression]].

**D2 — junit-platform-* staged only (FIXED, commit 1fa8e26b5).** Launcher host-side NoClassDef'd on
`HierarchicalTestEngine`. `junit-jupiter-engine` was a realm library (direct dep, flat) but its
transitive `junit-platform-engine`/`-launcher`/`-commons` were staged-only, so the flat
`JupiterTestEngine` could not load its bundle-only superclass. Fix: realm-library flag PROPAGATES
transitively over mandatory imports (`StagingClosure.propagateRealmLibraries`) — a flat library needs
its imported packages flat too. Same boot-stack guard.

**D3 — SessionSeed never registered (FIXED, uncommitted at time of writing).** Scenario played but
`SeedRun` was null ("the SeedRun was not seeded"). `SessionSeed` is BOTH the seeder (`into`) and the
`TestInstancePostProcessor` (`receiveSeed`), but `Main` used it only as seeder — it was never a Jupiter
extension on the scenario, so the post-processor never fired. Fix: `@RegisterExtension public static
final SessionSeed<SeedRun> SEED` on `ClusterSeedScenario` (single-sources the channel, `Main`
references it). A field `@RegisterExtension` is needed because the channel carries constructor state
(type + key) `@ExtendWith` can't supply.

**D4 — REALM_BOUNDARY gate blind to type=contract (FIXED, commit 147f21f34) — THE GATE THAT LIED.**
The whole point: the gate that should have caught D5/D6 at build time said nothing, not even a WARN.
Its "forbidden" (bundle-only) package set was built from `isDomain()` carriers ALONE, so
`type=contract` bundles (`manifests-contract`) never entered it — a flat class naming a contract type
leaked past. Fix: a single `isBundleSide()` = `isDomain() || isContract()` at all three decisions
(forbidden set, flat-realm exclusion, per-bundle scan). After the fix the build correctly WARNs 4 real
leaks (HostTreeHead, ManifestLinkPolicy, ControlplanePolicy, EntryGatePolicyEnforcer →
manifests.contract). Lesson: a gate keyed on one nature (`isDomain`) silently ignores the sibling
nature (`isContract`) that shares the same runtime property (staged, not flat).

**D5 — HostTreeHead (I6a, MINE) leaks manifests.contract host-flat — see the CORRECTION below.**

**D6 — EntryGatePolicyEnforcer → ManifestUpdateGate (pre-existing debt).** Same anti-pattern as D5,
documented in `controlplane/package-info.java` as a known WARN backlog. This is the crash that still
blocks the preview at the first WHEN (preflight). Not yet resolved.

## The I6 host-tree CORRECTION — host-entry CRUD is OSGi-side, in dedicated INCUS scenarios

The design (host-cellar-realisation-spec, atlas/seed Diagram V) said the HOST at grow reads the head
and writes the live/drift entries, with `HostTreeHead` folding *host-side*. The preview PROVED that
wrong (D5): `HostTreeHead` lived flat in seed-master and decoded `Host*Entry` from `manifests-contract`
(a `type=contract`, bundle-only bundle) — the very leak the bnd bans.

**The ownership — incus, not manifests (user, 2026-07-14).** First reflex was to give the host-tree
CRUD to manifests (that is where `Host*Entry` sat). WRONG: the HOST TREE (`host.N.staging.d` entire —
`rke2-manifests.d` + `k8s-daemonset.d` + `systemd.d` + cloud-config + env-overlay …) is what the INCUS
instance MOUNTS. incus owns the mount, picks the rotation slot, triggers the grow, measures the drift
of the WHOLE tree. `rke2-manifests.d` is just ONE folder among many — manifests is a CONTRIBUTOR of
content, not the tree's owner. This is the same reasoning the spec already used on the validate side:
"only incus sees the WHOLE of what the instance will mount, so only it can validate global coherence" —
so the tree's CRUD/fold/rotation/drift is incus's too.

**The correction.** The whole host-entry CRUD (publish the staging entry · fold the head · pick the
slot HostSlotSelector · the two deltas HostTreeDiffer · promote live/drift) runs INSIDE the OSGi world
as DEDICATED INCUS scenarios contributed through `IncusBddScenarios` (a domain hosts N scenarios, like
doctor), incus triggering them at the grow (it mounts, runs over the same FS). The `Host*Entry` records
move to `incus-contract` (host-tree vocabulary is incus's; incus already has its contract) — no
host-flat reader, so no leak. manifests keeps its role: materialise CONTENT into the SOIL subfolder
incus hands it; incus chooses the slot, hands the SOIL, checksums/publishes the tree entry. The host
keeps ONLY the Pulumi bind (mount `host.live.d`, I6e).

**What this deletes:** `HostTreeHead`, `HostSlotSelector`, `HostTreeDiffer`, `HostTreeDelta`,
`HostTreeDeltaRenderer` in seed-master (the I6a/b/c host-flat code, commits 0f5ccca2 / 926c78422 /
a050cac4 / c4c97db9) — moved OSGi-side into incus. Why dedicated scenarios not one flow: the
multi-scenario front-door pattern (`IncusBddScenarios` selecting a scenario) is already proven and
cheaper than swelling the provision scenario. OPEN sub-decision: `Host*Entry` home = `incus-contract`
(the natural default — the tree is incus's) unless a neutral host-tree contract earns its place.

**Graved (2026-07-14):** host-cellar-realisation-spec § "CORRECTION (2026-07-14): the host-entry CRUD
is OSGi-side"; atlas/seed § the CAUTION note after Diagram V. User chose "graver d'abord, coder après"
— the code refactor (D5 + D6, same anti-pattern) is the NEXT work, tracked by the now-fixed
REALM_BOUNDARY gate WARNs as a ratchet (leak count → 0, then raise the gate to ERROR).

See [[gson-flat-vs-staged-regression]] [[master-execution-stage-missing-state]]
[[controlplane-to-osgi-migration-frame]] [[cellar-extended-resource-producer]].
