---
name: docrepo-dag-state
description: "docrepo-dag-wip — a NEW repo: an OSGi-resolution + P2P-provisioning UNIT REPOSITORY (git-like DAG, self-hosting). MODEL COMPLETE; V1 SPEC WRITTEN. Code ABSORBED into rke2lab reactor (branch feature/unitrepo-resolution-core, now 9 commits ahead, local-only, NOT merged/pushed, KEPT as-is). ★ RESOLUTION-TRACK REAL-GRAPH PROOF COMPLETE (all 8 TDD tasks done, subagent-driven w/ spec+quality review each, surefire-verified): standalone Felix UnitResolver computes rke2lab's REAL cross-layer closure (8 modules + 10 domains + 28 units = 46 in one resolve), subsuming the hand-rolled ManifestsUnitDependencyApplier. NEXT = the MIGRATION track (retire the walker) or fold forward; see the ★★ RESOLUTION-TRACK PROOF section."
metadata: 
  node_type: memory
  type: project
  originSessionId: fad25661-6d06-4825-8ce2-6e3bbdbbafd5
---

**★ UPDATE 2026-06-20 — the design is being UNIFIED INTO rke2lab (design-level).** rke2lab matured
(R4 framework boots in prod, `UnitResolver` in prod, walker RETIRED — so the "NEXT = migration track"
below is DONE). The atlas-additivity ritual was run against this spec: it HOLDS, additive, and HALF
already shipped. The live unification work + the 2 settled decisions (mechanism C dropped; handler edge
= 2 axes) + the capability-anchoring atlas device now live in
[[rke2lab:unitrepo-design-unification-state]]. The paragraphs below are the 2026-06-13/15 design record
(still valid as the deep model/why); read them as history where they say "NEXT = migration track".
docrepo-dag-wip itself is STILL local-only/never-pushed — securing it is the standing risk.

A NEW standalone repo **`/private/var/lib/git/nxmatic/docrepo-dag-wip`** (local only, no
remote; working name, rename later). Reconstructs a design the user lost. Substrate under the
user's existing federated OSGi system ([[rke2lab:serviceloader-specialist-spi]]). Generalizes the
rke2lab doctor's record substrate.

**★ STATE after 2026-06-13 spec session: V1 SPEC WRITTEN, repo RESTRUCTURED. NEXT STEP =
user reviews the spec (the brainstorming gate), THEN writing-plans skill.** The spec is the
terminal artifact of brainstorming; writing-plans is the only skill to invoke next.

## Repo layout (post-restructure — this changed a lot this session)

- **`docs/architecture/` = the durable model, reorganized BY CONCERN, each C4/UML figure
  INLINE next to the prose that defines it** (user: figures are his PRIMARY way to understand
  a subject → must be at-point, always visible, with cross-links to related subjects). Five
  docs: `model-overview` (thesis + homogeneity axiom + C4 L1 + map of the others),
  `document-model` (anatomy figure + living-entity/extender/constitutive-handler-edge/lens-
  severance/uses:), `node-and-resolution` (C4 L2/L3 + dynamic loop + durability/retention/
  serve-own-DAG), `stack-as-document` (N1/N2/N3 + Pulumi identity + dogfeeding), `life-model`
  (K1 + k8s-workloads/fractal-mesh/reflexive-bootstrap, v2+ horizon). Plus `integration-atlas`
  (relinked) and `brainstorm-log.md` (the running thread / source of WHY).
- **`c4-preview.adoc` is GONE** — it was a figure dump mis-framed as "preview"; its figures
  were redistributed into the 5 concern docs and the file deleted. `diag-*.svg` stay gitignored.
- **NEW durable doc `docs/architecture/jgit-docrepo-transposition.adoc`** (committed to main,
  `904009e`) — the JGit↔docrepo fidelity map + the **option-B decision** (see below).
- **`docs/.wip/specs/2026-06-13-docrepo-dag-v1-design.md` = the V1 spec.** Lives in `.wip/`.

## The .wip convention (user's model, learned this session — see [[superpowers-assets-in-wip]])

`.wip/` sub-folders are **TRACKED + have history ON BRANCHES** (survive reloads), but are
**STRIPPED FROM HISTORY at merge/squash → main**. main NEVER contains `.wip/`. The forcing
function: before merging, anything durable in `.wip/` must be REDISTRIBUTED into the tracked
docs, else it vanishes at squash. NOT gitignored (I made that mistake first and reverted it —
gitignore would kill the on-branch tracking). The generalized merge-strip HOOK is the user's
SEPARATE conversation (he's building the `.wip` pattern there); I only adopted the folder + the
discipline in this repo, no hook yet.

## Git topology (rebuilt this session — user approved the rewrite; local-only so safe)

- **`brainstorm/docrepo-dag-model`** = the 19 original commits, FROZEN pristine (the honest
  brainstorm trace, incl. old c4-preview). The safety net.
- **`main`** = REBUILT: **empty root** (`b1f0228`, user's convention we'd missed) → consolidated
  durable commit (`8a6babf`: 5 arch docs + atlas + brainstorm-log + README + config) → **JGit
  transposition** (`904009e`: option-B doc + cross-links + brainstorm-log entry). ZERO `.wip/`.
  Verified disconnected from brainstorm/ (orphan worked).
- **`wip/docrepo-v1-spec`** = main + 1 commit (`7edab6c`) carrying the spec in `docs/.wip/specs/`
  (rebased onto the option-B main). Where writing-plans works; graduates to main later.

## ★ OPTION B DECIDED — a docrepo document IS a git tree, ContentStore built on JGit

Spec-review raised: build `ContentStore` on **JGit** (already a seed-master dep — used in
GitMetadataExtractor + EntryGatePolicyEnforcer) not hand-rolled. Real question = FIDELITY (does our
exposed model apply git's own principles so JGit sits underneath decorrelated?). Verified vs primary
sources. **DECISION = option B (documents ARE git trees; Visit = commit; severed lens = `subtree
split`), NOT A (JGit as bare hashmap).** ★ **CORROBORATION (user): B is EXACTLY the path the LOST
system took, to rest on proven logic** → retrieved-memory converging with reasoning, a fidelity
landmark (like "OBR without p2", "data+logic not separated"). Why safe: we ALREADY do bottom-up
reconstruction (doctor's medical record rebuilt from Pulumi) → "ingest a git DAG" == "ingest a Pulumi
checkpoint" == the SAME parse. Bonus: native fetch/pull/push/rebase + dedup + reflog + GC. Cost:
documents serialize to canonical git trees, hash = git's (SHA-1→SHA-256, impl detail behind the
interface). **Precedent = JGit `ObjectDatabase` SPI** (~3 methods: newInserter/newReader/close +
has/open; subclasses ObjectDirectory on-disk + DfsObjDatabase off-disk = Google hosted-Gerrit
lineage; JGit's words "swap backends, keep object-model semantics" = our thesis). Discipline:
ContentStore speaks hash/bytes/document, NEVER ObjectId/RevWalk. **TWO ORTHOGONAL NAME-SPACES** (the
organizing axis, reused from uses:-vs-closure): content-hash space (closures/versions/TRANSPORT) =
git HAS it; schema-name space (requirements/handler-edge/uses:/RESOLUTION) = git has NONE, ours. ⇒
transport NOT a hard problem (hard half=resolution=ours; easy half=move-a-closure=git-native).
**CONFLICTS ≠ git merges**: ours = incoherent-closure (uses: broken), resolver REFUSES → unmet-need-
with-why (ReferralReply/Assessment), node stays put, NOTHING merged; we use JGit's STORE not its
merge. **SUBTREES**: object-model-subtree=our transfer unit ✅; `subtree split`=severed lens ✅;
submodule/gitlink (pointer-without-ownership, source dies→objects lost)=REJECTED proxy model (git
documents the trap World-A eliminates). Latent tension RESOLVED: spec's byte-ContentStore read like A
but life-model's "migration=git clone" needs B → B reconciles. Full map = jgit-docrepo-
transposition.adoc; brainstorm-log has the resolved-fork entry.

## ★ TERMINOLOGY — "document" → "Unit"; the system is the UNIT REPOSITORY

Spec-review found "docrepo"/"document" mis-calibrated. OSGi names its repo after its unit (bundle
→ bundle repository); OUR bundle-equivalent in rke2lab = **`Unit`** (ManifestsUnit 38 / SystemdUnit
33 already Provide/Require+resolve; Visit = state-side instance). Generic supertype maps to OSGi
`Resource` (Core ch.6 capability bearer), NEVER `Bundle`. NOT "bundle" (keeps its precise OSGi
meaning — a handler IS a bundle; collision in conversation, user's own anti-collision reflex). NOT
"resource" (collides Pulumi `Resource` 31 uses + OSGi API). **★ user's clinching insight: a Unit is
mainly CONFIG + LOGIC (package/script/manifest) = DATA + BEHAVIOUR = the living-entity thesis — the
word SAYS it.** Recorded as the naming rationale in unit-model.adoc. PROPAGATED this session
(commit `2ab41d8` on main + spec on wip): document→Unit, docrepo→unitrepo, modules
unitrepo-core/-handler-api/-pulumi, ns unitrepo.*, scheme unitrepo:// (all PROVISIONAL, freeze at
absorption). Docs renamed: document-model→unit-model, stack-as-document→stack-as-unit,
jgit-docrepo-transposition→jgit-unitrepo-transposition. brainstorm-log HISTORY preserved (append-only;
terminal TERMINOLOGY entry added, not rewritten). The repo DIR docrepo-dag-wip keeps its name (still
provisional). NB the memory note above/below still says "docrepo/document" in older paragraphs — read
as historical; the live term is Unit / unit repository.

## ★ NEXT SESSION (the bridge to impl) — absorb the unit repository into rke2lab

Decided with user (do NOT start cold without this plan): bring the unitrepo design+code INTO the
rke2lab reactor. Mechanics: (1) fetch docrepo-dag-wip's history into rke2lab as a SEPARATE ARCHIVE
branch (e.g. `archive/unit-repository-design`) — preserved/recoverable, NOT merged to mainline (the
two repos COLLIDE on docs/architecture/integration-atlas.adoc, so no flat merge); (2) on a feature
branch, REDISTRIBUTE the renamed docs into rke2lab's tree — OPEN DECISION: unitrepo atlas becomes a
SECTION of rke2lab's atlas, or a neighbour under docs/architecture/unit-repository/ beside doctor/;
(3) scaffold the 3 modules (unitrepo-handler-api / unitrepo-core / unitrepo-pulumi, flat top-level
like systemd-contract) + add `org.apache.felix:org.apache.felix.resolver:2.0.4` to bom (JGit 7.2.0
already there). Subtree-IN-SPIRIT (ownership/pull=own — dogfoods the very model), archive-branch in
mechanics. Deferred from this session deliberately (long context; big git-topology op wants fresh
context; it IS the natural bridge into the V1 build). THEN writing-plans for the V1 impl.

## THE V1 (what the spec scopes — buildable, latent)

seed-master AS the level-0 node, single-node, in-process, **LATENT** (changes nothing in
provisioning; reads checkpoints that already exist, like MedicalRecordDump). Three caps + one
proof: (1) **ingest** each checkpoint as a content-addressed Visit-document — REUSES the
already-built `pulumi-automation-ext` `StackCheckpoint` (reshapes to `com.pulumi.automation.
StackDeployment` envelope) + `StackSnapshot` (the costly half exists; CONFIRMED code-faithful
this session); (2) **resolve** via **Felix Resolver standalone** (no framework); (3) **load**
ONE trivial extender-handler from the store via a **parent-first child ClassLoader** =
mechanism C. Proof = handler-from-store handles a Visit-doc ("use a document" == "run a
module"). **Open placement question flagged in the spec §4.1:** V1 code in rke2lab reactor
(reuse StackCheckpoint via reactor, per CLAUDE.md) vs in docrepo-dag-wip — user to decide.
V1 DEFERS (no rework): gocloud node-backend write-side, federation/mesh, k8s life-model, live
re-resolution/hot-swap, lenses/anchors/reflog/pruning, full OSGi lifecycle, uses:-extractor.

## THE THESIS (the original contribution)

OSGi's *resolution model* + a *P2P provisioning/history layer* neither OBR nor p2 targeted.
Lost system = OBR-based, NO p2/Equinox, but LACKED provisioning/history. p2 HAS it but
non-P2P (composite repos = referral/mirror tree = rejected proxy model). Stay faithful to the
OSGi model, do NOT start on Equinox. The git-faithful decisions ARE p2's missing value layer
in P2P form. OSGi reuse is standalone, NO running framework (verified vs Core/Compendium R8 +
Felix): generic capability model (Core ch.6), custom namespaces (Core §3.3), Resolver (Core
ch.58, Felix resolver 2.0.4), Repository XML (Compendium ch.132), uses:/class-space (Core §3.7.6).

## Model essentials (all forks resolved — full reasoning in brainstorm-log.md)

git = distribution/storage (content-addressed DAG, pull=own, sparse requirement-driven repl);
OSGi = requirement vocabulary. Self-hosting keystone (app IS a documents DAG AND its state IS
a documents DAG → reflexive). Live resolution → non-deferrable level-0 kernel (only
non-document). World-A durability + demand-driven prune (alive-iff-required = distributed GC).
Retention = reflog + opt-in anchors (role not tier). **Node homogeneity axiom** (same kind,
differ by domain, no special node types). Serve OWN DAG, never proxy (reachability via
ownership replication). Oriented view = subtree closure; **lens = SEVERED projection** (no
back-edges → makes hiding work, can be generative), holder-gated (= rke2lab GrantPolicy).
**Document = LIVING ENTITY** (data+behaviour); logic binds via **extender pattern**; the
**handler edge is CONSTITUTIVE** (orients the graph; no pure-data document; bare blob = inert
leaf, not a node; graph connected by construction). **uses: = agreement constraint in
SCHEMA-NAME space** (orthogonal to content-hash closure; severed lens carries it by SchemaRef
name; extracted from a concrete artifact, never hand-declared). Life model: nodes = k8s
workloads, scale-from-zero via activator (wakes ≠ proxies content), fractal mesh, reflexive
bootstrap (seed-master migrates IN by a pull = git clone, physical erases). Pulumi node-backend
path = a stack IS a documents DAG (DAG GIVEN by Pulumi state, not constructed); SPI verdict:
A=Automation-API no-fork+ALREADY-BUILT, B=gocloud blob driver needs custom binary (= node not
server), C=full Backend iface = fork.

## Open implementation-validation items (NOT design unknowns)

Byte-exact checkpoint round-trip + secrets through the storage seam (prove Pulumi integrity) —
THE one not-yet-proven atlas edge; node identity (peer-id + root hashes); dynamic-loop ordering
(re-wire BEFORE prune); per-namespace uses:-extractor (the one inherited engineering burden).

## ★ FRAMEWORK MOVE + PULUMI MEDIATION LAYER (2026-06-15, commit 032f3aa on docrepo-dag-wip main)

User reopened the loading fork: hand-rolled ClassLoaders (mech C) = re-writing OSGi; OSGi should
be the SUPPORT (the lost system rested on it — publishing a bundle through the repo was natural).
RE-BRAINSTORMED → **the fork DISSOLVED, not picked.** mechanism C is the EMBRYO of OSGi-embedded
(same architecture: host loader holds shared types, bundle world holds behaviour; OSGi just adds
lifecycle axes 1+2 monotonically) — so "child-classloader vs framework" is ONE axis at two
maturities, not a fork. **The clean resolution (user): define the API the OSGi world needs to talk
to the Pulumi proxy = a MEDIATION / anti-corruption layer** (domain terms in, gRPC hidden on the
host classloader behind it). Same discipline as the JGit ObjectDatabase seam.

**★ GROUNDED THE REAL CONTRACT (Pulumi Java SDK 1.28.0 on disk + pulumirpc protos, VERIFIED, not
guessed).** seed-master↔engine = gRPC; in `binary:` mode (Pulumi.yaml today) the engine launches
seed-master as language host, SDK opens 2 client conns back (PULUMI_MONITOR/PULUMI_ENGINE). LIVE
surface is NARROW: `ResourceMonitor` (GrpcMonitor, client): registerResource, **registerResourceOutputs**
(the per-resource write-side medical-record/ledger already use), readResource, supportsFeature,
invoke, call, registerPackage; `Engine` (GrpcEngine, client): log, getRootResource,
requirePulumiVersion. `LanguageRuntime` (engine→us) = **SDK-internal** (LanguageRuntimeImpl/
InlineLanguageHost), seed-master NEVER implements it → not our surface. Rest of pulumirpc
(Analyzer/Callbacks/Converter/Events/ResourceProvider/ResourceStatus) NOT driven across our boundary.

**CONSEQUENCES (all grounded):** (1) **#1565 (gRPC-under-OSGi) cannot occur by construction** — no
gRPC/pulumirpc type crosses into the bundle world, the TCCL discovery bundle-isolation breaks runs
host-side only; (2) the mediation layer **IS the already-decided `unitrepo-pulumi` module** — role
sharpened from "ingest adapter" to "the ACL that speaks gRPC so nothing else has to"; (3) ★ **V1
ingest touches NO gRPC** — ingest = history-fold of a file:// backend (what MedicalRecordReader
does); so V1's whole chain proves WITHOUT crossing gRPC↔OSGi, the hard unknown is OFF V1's path,
framework-move + mediation = safely v2.

**THE PAYOFF (why the user wanted it): it FRAMES the UNIT LIFECYCLE.** clone/transport/reconcile/
reload/deploy split cleanly across the SAME two name-spaces (content-hash vs schema-name) + the
narrow engine seam — NO fourth concept needed (well-formedness signal). clone/transport/reload =
content-hash seam (ContentStore/JGit, git-native); reconcile = schema-name seam (resolver REFUSES
incoherent closure, narrates why — NOT a git merge); deploy/record = engine seam (recordOutputs +
history-fold = dogfeeding write half). Each FRAMED, not resolved — next rounds of design. New durable
doc `docs/architecture/pulumi-mediation-and-unit-lifecycle.adoc`; atlas inventory + 3 cross-links +
brainstorm-log updated.

**★ gRPC-IN-PULUMI vs OSGi — evidence doc + ECF (2026-06-15, commits 04293f0 + 11bb221 on main).**
New durable doc `docs/architecture/grpc-in-pulumi-and-osgi.adoc` (grounded on SDK 1.28.0 jar + io.grpc
1.80.0 jars). Facts: seed-master is BIDIRECTIONAL (LanguageRuntime gRPC SERVER the engine calls Run
into = SDK-provided; ResourceMonitor/Engine CLIENT it drives back); grpc/netty NOT shaded into the
pulumi jar (external, grpc-bom 1.80.0); the OSGi pain is the MECHANISM not the symptom — client uses
ManagedChannelBuilder.forTarget → grpc-core discovers Netty transport via **ServiceLoader keyed on
TCCL** (verified provider files in grpc-netty: ManagedChannelProvider/ServerProvider/
NameResolverProvider), which bundle-isolation breaks; "no OSGi headers" (#1565) + split packages
(io.grpc across core/api/netty/util) + Netty native fragments compound it. Route around it by
construction (keep gRPC on flat host loader, no gRPC type crosses into a bundle).
**★ ECF PRECEDENT + DEPENDENCY EVAL (user pointed to grpc-java #6981 then ECF/grpc-osgi-generator).**
ECF (Scott Lewis) already runs gRPC inside OSGi as a Remote Services provider → proof-of-existence +
2nd friction axis (gRPC async/StreamObserver vs OSGi sync services → needs a paradigm bridge; our
sync domain-iface gives it free) + lesson "bridge belongs in the INTEGRATION layer, not grpc-core" =
our unitrepo-pulumi ACL. Evaluated the tooling as a DIRECT dep → **SPLITS BY SEAM** (the decision):
SEAM 1 (Pulumi engine) = hand-written ~7-method façade, NOT grpc-osgi-generator (wrong direction — we
are CLIENT of pulumirpc which Pulumi OWNS, the generator exposes a proto YOU own; output is
reactive+proto-typed not domain; drags RxJava2+reactive-grpc into the thin module). SEAM 2 (units as
distributed OSGi services across the federation, v2) = the ECF stack (generator +
**grpc-RemoteServicesProvider**) is a candidate SUBSTRATE — it does export/import of OSGi-services-
over-gRPC = units-between-nodes = the federated-OSGi north-star [[rke2lab:serviceloader-specialist-spi]];
SPIKE-GATED risks: needs full OSGi+RSA runtime (commits to the complete framework-move), grpc 1.39
(ECF) vs 1.80 (Pulumi) skew, reactive may be a FEATURE here. Two seams want two tools = the
two-name-space split corroborated again.

**★ FEDERATION TRANSPORT — OPEN/DEFERRED/PLUGGABLE (2026-06-15, commit 98a37c5 on main).** User
reframe: gRPC is FORCED on the Pulumi seam but only an OPTION on the federation seam, and NO
requirement forces it yet. Surveyed OSGi Remote Services (primary sources): **RSA is transport-
agnostic by spec (Compendium R8)** — 4 roles (Remote Service Admin / Topology Manager / Discovery /
Distribution Provider), wire protocol chosen PER SERVICE via config types (service.exported.configs),
NO protocol mandated → transport = a LATE SWAPPABLE slot, never baked in. Providers: **Aries RSA**
(active, R7 REFERENCE impl, tcp/fastbin), **ECF** (active, R8 RS/RSA in the OSGi TCK, multi-provider
incl gRPC), **CXF-DOSGi** (ARCHIVED 2022, dead). DECISION = capture the knowledge, DEFER the choice
until a need exists; locked = only the SHAPE (behind RSA config types). Sharpens the ECF note: ECF is
NOT "the gRPC option for federation" — it's A standard RSA impl that happens to include gRPC; the
transport-question and the ECF-question are SEPARATE. grpc-in-pulumi-and-osgi.adoc gains a "federation
transport is OPEN" subsection; atlas gains a distinct deferred/pluggable federation-transport row,
kept apart from the Pulumi mediation row.

## ★ BUNDLE DECOMPOSITION (v2 target, by capability namespace) (2026-06-15, commit b28c242 on main)

User: re-compose HOW we build, now the decomposition is clear + north is OSGi-native. Precisions
held: "native OSGi" = v2 NORTH spike-gated (V1 stays mech C); Maven-module ≠ OSGi-bundle (build vs
runtime/classloader axis); not everything becomes a bundle (mediation IMPL stays host-side). Scope =
WHOLE REACTOR → OSGi (I flagged YAGNI then CONCEDED — the self-hosting keystone IMPLIES it: app = DAG
of modules = sub-DAG of units = bundles). Spine (user choice) = BY CAPABILITY NAMESPACE (Provide/
Require), the resolver view.

**★ KEY FINDING (verified in code): the resolution model ALREADY EXISTS in ad-hoc strings.**
ManifestDomainCatalog = 10 domain IDs (cicd/cluster/cluster-api/gitops/high-availability/mesh/
networking/platform/runtime/storage) = Provide-Capability coords in waiting; ManifestsUnit.
manifestUnitId + dependsOnManifestsUnitIds + dependsOnDomainIds = Require-Capability; manifests has a
HAND-ROLLED dependency WALKER over those edges. ⇒ decomposition = RE-EXPRESSION not invention (Felix
Resolver replaces the walker, no new graph; same "read structure already there" as ingest).
Namespaces: osgi.extender (handler edge), unitrepo.unit, unitrepo.manifest.domain (x10),
unitrepo.systemd.unit, unitrepo.netplan/cdk8s, osgi.content. uses: rides on top.

**★ gRPC FAN-OUT (COUNTED) DRAWS THE BUNDLE/HOST LINE:** systemd-contract 0 / netplan 0 /
cdk8s-systemd 0 = clean bundles (already /api-structured → Export-Package reads off existing
boundaries, no re-carve; package-private-sweep paid off); manifests 1 (isolate); pulumi-automation-ext
3 = host (the Pulumi seam); **sdks/incus 175 = ALL gRPC (generated Incus provider) = host-side NEVER a
bundle** (re-acquires #1565); seed-master 23 = host. ⇒ "whole reactor → bundles" = BUNDLE layer
(gRPC-free domain) + HOST layer (gRPC/engine) + mediation seam; sdks/incus = the honest exception
proving the rule. The dep DAG: bom/netplan/systemd-contract/cdk8s/pulumi-automation-ext = leaves;
manifests → cdk8s+netplan; seed-master → everything.

**MIGRATION ORDER (atomic each, monotone, leaves-first):** 1 systemd-contract (pilot, 2 files all
/api) → 2 netplan+cdk8s → 3 manifests (retire walker, isolate 1 gRPC file) → 4 unitrepo-core +
handler-api (new, born bundles) → 5 host boundary + mediation iface = THE spike (gRPC-host↔OSGi,
gated). Steps 1-3 = pure additive re-expression, could start without the framework move; V1
unaffected. New durable doc `docs/architecture/bundle-decomposition.adoc`; atlas decomposition row;
model-overview + node-and-resolution cross-link.

## ★★ SPIKE PASSED — the framework move is DE-RISKED on the real stack (2026-06-15)

The load-bearing v2 unknown (can OSGi host the node while gRPC stays host-side, in OUR stack?) was
SPIKED before writing the migration plan — user agreed we should de-risk before authoring. Throwaway
branch **`spike/osgi-grpc-host`** in worktree `.claude/worktrees/spike+osgi-grpc-host` (off rke2lab
main; sops re-smudged per [[sops-worktree-smudge-noise]]). A JUnit test in seed-master's REAL classpath
(Java 25, grpc 1.80 + grpc-netty, Pulumi 1.28, the shade): embedded **Felix 7.0.5** (test scope),
exported the seam package via `org.osgi.framework.system.packages.extra`, registered a host-side
`HostGrpcSeamImpl` as an OSGi service, synthesized a real bundle JAR on the fly (Bundle-Activator +
Import-Package), and the bundle called through the seam to a host-side `ManagedChannelBuilder.forTarget`.
**RESULT: PASSED** (Tests run 1, 0 failures) — the channel returned through the bundle→seam call is
`io.grpc.internal.ManagedChannelOrphanWrapper` (a REAL built channel) ⇒ gRPC's transport-provider
ServiceLoader discovery (the #1565 crux) SUCCEEDED from inside an OSGi bundle context. All 3 kill
criteria cleared: Felix started (ACTIVE), the bundle saw the host package, forTarget did NOT throw
"no functional channel provider". **THE MEDIATION DISCIPLINE that makes it work, now PROVEN not just
reasoned: the host-side impl pins the TCCL to its own (flat) classloader before the gRPC call**
(`setContextClassLoader` then restore) — so discovery runs where grpc-netty's META-INF/services is
visible, regardless of the caller's bundle TCCL. ⇒ the bundle/host split + mediation seam HOLDS on our
stack; the migration plan's loading half (step 5) rests on FACT, not assumption. NB also validates ECF's
lesson concretely (sync seam in front of gRPC, bridge owned by the integration layer). Spike is
THROWAWAY — branch kept until the verdict is folded into the plan, then delete (it proved its point;
the keepable artifact is this knowledge + the TCCL-pinning pattern, not the test).

## ★★ FIRST CODE LANDED — resolution-core runs for real (2026-06-15, branch feature/unitrepo-resolution-core)

User: "apply and get the benefits." Took the SAFE half (the resolution track, de-risk-free) into the
rke2lab reactor. Isolated worktree `.claude/worktrees/feature+unitrepo-resolution-core` (off main, sops
re-smudged), branch **`feature/unitrepo-resolution-core`** — **COMMITTED `d13961ea`** (not merged;
main untouched at db66bb3e; the unrelated `.flox/env/manifest.lock` change deliberately left out).
Two NEW reactor modules (flat top-level, registered in root <modules> before seed-master):
- **`unitrepo-handler-api`** — the `UnitHandler` SPI iface (handledType + handle), the extender-binding
  / mediation-seam embryo, on the shared loader.
- **`unitrepo-core`** — `UnitResource` (a unit as an OSGi generic Resource, Core ch.6, fluent
  provide/require), `CapabilityFilter` (RFC-1960 via `FrameworkUtil.createFilter` — STANDALONE, no
  framework), `UnitResolver` (wraps **Apache Felix `ResolverImpl(new Logger(LOG_ERROR))`**, builds a
  `ResolveContext` whose findProviders matches namespace+filter over the universe).
bom gained felix.resolver **2.0.4** + **`org.osgi:osgi.core:8.0.0`** (NB the correct GAV is
`osgi.core` NO prefix — `org.osgi.core` only exists ≤5.0.0 on Central; the wrong GAV fell through to a
GitHub 401). **★ TEST GREEN, VERIFIED VIA SUREFIRE (not silence — [[rke2lab:build-verification-gotchas]]):
`UnitResolverTest` Tests run: 2, 0 failures** — (1) a Provide/Require closure (root → manifest-domain
dep + constitutive osgi.extender handler edge) resolves to the right 3-unit closure via Felix
STANDALONE; (2) an unsatisfiable requirement THROWS ResolutionException (errors-as-values, not silent
empty — [[error-handling-layered-contract]]). ⇒ the hand-rolled manifests dependency-walker IS
replaceable by this resolver, proven on the real stack (Java 25, reactor -am build, Felix 2.0.4).

**★ DECISION — bnd DEFERRED to the loading track (user).** Concern A (consume the OSGi resolution API
as a library = what we built) needs NO bundle tooling — the resolver runs over our own UnitResource
data objects, plain-JAR is correct, this is the deliberate "standalone resolver, no framework". Concern
B (produce real loadable BUNDLES with Bundle-SymbolicName/Export-Package) needs tooling — and the choice
is **bnd, NOT Tycho** (grounded in our own decisions: we rejected p2/Equinox at the thesis level, and
Tycho IS the p2/Equinox/PDE manifest-first build chain; bnd is Maven-first, Felix-aligned, and its
headline feature — compute Import-Package/uses: FROM BYTECODE — literally implements the transposition
doc's premise). bnd is build-time only (no framework), so it COULD land in the resolution track, but
user chose to DEFER it to step 5 (the loading track) → unitrepo-* stay plain-JAR standalone-resolver
libs for now. NEXT when resumed: commit decision for this branch, then either continue the resolution
track (leaf modules declare capabilities: systemd-contract pilot) or fold into the migration plan.

## ★★ RESOLUTION-TRACK PROOF — COMPLETE + MERGED TO origin/main (2026-06-16)

Resumed, FINISHED, and **MERGED** the resolution track. **★ origin/main is now `ce6782b9`** (merge
commit, no-ff) — the user reversed the earlier "keep local-only" call so the OTHER conversation could
start its `fresh` from updated main. Sequence: merged `feature/unitrepo-resolution-core` into local
main (clean, disjoint file sets) → verified on the merged result (**`Tests run: 265, Failures: 0`** full
seed-master closure, the 4 realgraph tests + UnitResolverTest + all 260 controlplane/bdd) → pushed
`7e5ec7d1..ce6782b9` (fast-forward) → **removed the external worktree + deleted the branch** (fully in
origin/main, only manifest.lock re-smudge noise present). The 9 proof commits (d13961ea resolution-core
+ d734c199 spec + 1e3ae5c2 plan + the 6 task commits 583edf62→aa3071da) are preserved under the merge.

The 6 proof commits on top of the prior session's 3 (d13961ea resolution-core + d734c199 spec +
1e3ae5c2 plan): **583edf62** (Task 1 requireAll verb) → **ab758927** (T2 seed-master test dep) →
**30fb360c** (T3 ReactorModuleCatalog) → **9e46fa3a** (T4 ManifestsUniverse) → **8113e7ad** (T5
UniverseBuilder) → **c8ec4b61** (T6 happy-path proof) → **57dafbcb** (T7 unsatisfiable-throws) →
**aa3071da** (final-review fix: drop inert addDefaultStageALinkableDomains). Executed via
subagent-driven-development — fresh implementer + spec-review + code-quality-review PER TASK, every
green VERIFIED by COUNTING surefire reports (never trusting BUILD SUCCESS), per [[build-verification-gotchas]].

**THE GOAL (this proof):** make the just-shipped Felix `UnitResolver` compute the REAL rke2lab
cross-layer closure in ONE resolve, replacing the synthetic `UnitResolverTest` fixtures — validating
it subsumes the hand-rolled `ManifestsUnitDependencyApplier` over real data. LATENT (reads structure
already there; does NOT retire the walker — that's the migration track). Framework-free (zero spike
exposure).

**DESIGN DECISIONS (settled via brainstorming, all in the spec `wip/specs/2026-06-15-
unitrepo-real-graph-resolution-design.md`):**
- **Both layers, ONE universe** (user: "we're in the real OSGi world" — Core ch.6 generic capability
  model is built for heterogeneous namespaces at multiple granularities in one resolve). Coarse =
  reactor modules; fine = manifest domains+units.
- **Linking = membership child→parent + cardinality:=multiple.** A child ADVERTISES its parent as an
  extra attribute on its own identity capability; the parent fires ONE `requireAll` matching members.
  `manifests` requires `(module=manifests)` once → all 10 domains; each domain requires `(domain=X)`
  once → its units. Real containment (`ManifestsDomain.units()` + reactor tree) re-expressed as a
  capability attribute = re-expression, not invention.
- **5 edge kinds, all real:** module→module (Maven deps), module→domains (membership), domain→domain
  (`dependsOnDomainIds`), domain→units (membership), unit→unit (`dependsOnManifestsUnitIds` — real
  chains exist, e.g. flux-root→flux-instance→flux-operator).
- **Coarse source = Option A: hand-transcribed `ReactorModuleCatalog`** (8 modules + edges, like
  `ManifestDomainCatalog` hardcodes domain ids), NOT pom-parsing (Option B, rejected: heavier than a
  proof needs). Fine source = `ManifestsUniverse` adapter reading the REAL `ManifestsDomainRegistry`.
- **Placement FORCED:** harness lives in **seed-master TEST scope** (only seed-master depends on both
  manifests + unitrepo-core; unitrepo-core sits below manifests). Sole prod change = `requireAll` verb.

**THE PLAN = `wip/plans/2026-06-15-unitrepo-real-graph-resolution.md`** (8 TDD tasks).
**★ FINAL STATE — ALL GREEN, surefire-counted on a full-reactor `clean package -pl :seed-master -am
-DskipTests=false` (BUILD SUCCESS):** UnitResolverTest **3**, ReactorModuleCatalogTest **1**,
ManifestsUniverseTest **1**, UniverseBuilderTest **1**, RealGraphResolutionTest **2**. The universe =
**8 modules + 10 domains + 28 units = 46 UnitResources**, resolved from the `seed-master` module-unit in
ONE `resolve()`; the closure spans all three layers, the **cardinality:=multiple anti-cheat** holds
(gitops `requireAll` fanned out to >1 unit), and the **unsatisfiable case throws** ResolutionException.
**Sole production change = the `requireAll` verb (+19 lines in UnitResource.java)** — verified: nothing
else in any module's `src/main` changed since the resolution-core baseline; the manifests
`ManifestsUnitDependencyApplier` walker is UNTOUCHED (its retirement is the MIGRATION track's job).

**Findings the per-task reviews caught (value of the two-stage review):** T3 dead `all()` method
removed (+ tautological test replaced with a real capability assertion); T4 implementer mis-counted
units as 27 — a spec-review LIVE PROBE corrected it to **28** (missed `gitops/sops-age`), confirming
the plan's 46; T5 shared-mutable-instance invariant clarified (capture `moduleById` once, drop a
redundant `Map.copyOf`); final review dropped the inert `addDefaultStageALinkableDomains()` (the harness
reads only `catalog.all()`; user chose no-dead-code over prod-fidelity).

**NB the corrected unit count is 28** (10 domains: cluster 1, storage 1, gitops 4, runtime 6,
networking 4, mesh 4, high-availability 1, cicd 2, cluster-api 3, platform 2). New feedback
memory this session: [[decision-options-in-preview]] (render option diagrams in the preview before asking).

## ★★ STEP-2 TARGET (decomposition + APIs) — design decisions captured via preview-driven brainstorming (2026-06-16)

After the merge, the user reopened "do we now switch the logic into seed-master for real + restructure
into OSGi plugins?". We split it into **TWO SEQUENCED chantiers** (user picked "les deux, séquencés"):
- **STEP 1 = retire the walker** (resolution track, in production, framework-free): make `UnitResolver`
  drive production manifest synthesis. **★ SPEC + PLAN BOTH WRITTEN & COMMITTED — ready to EXECUTE.**
  Branch **`design/step1-walker-retirement-spec`** (external worktree `rke2lab.d/feature/step1-walker-
  retirement-spec`, base origin/main `abc56741`). Two committed `.adoc` files in `wip/superpowers/`
  (NOT `docs/`, per [[superpowers-assets-in-wip]]; `.adoc` so it versions into real docs at merge):
  spec `wip/superpowers/specs/2026-06-16-walker-retirement-design.adoc` (commit `028fb863`), plan
  `wip/superpowers/plans/2026-06-16-walker-retirement-plan.adoc` (commit `951d3ad7`, 6 TDD tasks).
  **★ DESIGN (post type-state revision — supersedes any earlier "keep validation" note):** the walker is
  a TRIO (unit-DFS `ManifestsUnitDependencyApplier` + domain-DFS `applyDomainWithDependencies` + entry
  `applyManifestsUnitWithDomainDependencies`) — DELETE all three. The resolver subsumes BOTH DFS levels in
  one resolve (proven). Visit order = topo-sort the `require` edges ONLY, excluding `requireAll`
  containment (structural, not temporal); cycle → topo-sort failure. **★ TYPE-STATE (the user's key
  objection — a constructor must BUILD, not run logic that throws):** model the missed state —
  `ManifestsDomainRegistry` becomes the *assembled* state (build-only, never throws on a malformed graph)
  → `resolve()` → `CoherentManifestsDomainRegistry` (carries the visit order; the ONLY type exposing an
  order). So DELETE the constructor validation too (`validate*` + helpers); `resolve` is the SINGLE
  coherence gate — compile-time guard (can't use an unresolved registry) + one runtime gate. **KEEP**
  the model accessors + the visitor seam (`ManifestsUnitVisitor`/`ManifestsUnitContext`/
  `Cdk8sApiObjectResolver`) UNCHANGED. **ADD** `manifests`→`unitrepo-core` compile dep + a registry-
  PARAMETERIZED `ManifestsUniverse` in `manifests/src/main` (production must read the EXISTING policy-
  filtered registry, NOT build its own all-enabled one like the test adapter does — the one production
  subtlety) + `ManifestsVisitOrder` + the type-state. Universe = FINE layer only (domains+units, root
  `requireAll`s all domains = the walker's scope); module layer stays the proof's/step-2's concern.
  **★ ONE OPEN QUESTION left to the plan's Task 4 (TDD):** the cross-domain rule (unit in A may depend on
  unit in B only if A→B) — a plain `require(unit=X)` ignores domains, so deleting `validate*` may drop it.
  Plan tries option a (encode in the universe → illegal cross-domain becomes an unsatisfiable resolve,
  proven by a failing-resolve test); falls back to option b (one explicit check on `CoherentRegistry`,
  invoked by resolve not the constructor) if encoding is unnatural.
  **★ RESUME = EXECUTE via [[subagent-driven-development]]** (user's standing choice — fresh implementer +
  spec-review + code-quality-review per task, surefire-COUNTED never trusting BUILD SUCCESS, per
  [[build-verification-gotchas]]). 6 tasks in order: 1 pom dep, 2 ManifestsUniverse, 3 ManifestsVisitOrder,
  4 cross-domain (a/b), 5 type-state + delete trio + rewire `DefaultManifestSynthesisService.buildAndApplyUnits`,
  6 synthesis-parity + full-reactor verify. The plan has exact paths/signatures/line-ranges + flox commands.
- **STEP 2 = decomposition + APIs** (migration track, gated on the OSGi-gRPC spike already passed).

**★ GROUNDED THE AVANT/APRÈS MAP (3 parallel Explore agents over post-merge `src/main`, gRPC import
count = the bundle/host signal — the #1565 ServiceLoader-TCCL constraint).** THREE SURPRISES vs the
prior bundle-decomposition note, all verified at the counter: (1) **`manifests` = ZERO gRPC** (note had
said "1 gRPC file to isolate" — there are NONE; it is the biggest but cleanest bundle candidate, ~100
classes pure domain + CDK8s synthesis); (2) **the bundle/host line runs INSIDE seed-master, not between
modules** — `bdd/` is BIFURCATED: 58 pure-diagnostic classes (bundle-eligible) vs 10 that glue the
ledger to Pulumi stack history (host); `policy/` (5 cl.) is pure too; (3) **`pulumi-automation-ext` has
a clean gRPC-free domain core** (`StackHandle`/`StackSnapshot`, pure file-I/O) — host only via its pom
dragging the Pulumi SDK. `sdks/incus` = 175 generated-gRPC classes = host-OBLIGATORY. seed-master
depends directly on `grpc-netty`.

**★ THE REFINED OSGi/HOST LINE (user's intuition affined, the key correction):** the line is NOT
"OSGi = seed-master's own resources, host = generated SDKs". The PITFALL: seed-master's OWN
`ComponentResource`s (`SeedManifestSynthResource`, `ClusterReadinessResource`, `InterventionResource`,
`SystemdAdapterResource`, `BboxReservationResource`) are ALSO host — because a Pulumi `ComponentResource`
calls the engine over gRPC AT CONSTRUCTION (`registerResource`/`registerResourceOutputs`). The real,
prettier split: **OSGi MODELS (the *what* — declarative domain + resolved closure + a pure DESCRIPTION
of resources to create); the HOST ACTUALIZES (the *how* — thin `ComponentResource` shells that receive
the description and register it via the engine).** This is exactly [[model-substrate-alignment]] /
[[specialist-as-ledger-northstar]]: pure model, host-actualized substrate.

**★ DECIDED — the model/host boundary is a NEUTRAL declarative description (user voted option 1).** The
OSGi bundle emits a pure `ResourceDescription` (record: type, name, args, dependsOn) carrying ZERO Pulumi
types. NOT `com.pulumi.core.Output` (risk: transitive gRPC re-contamination — rejected). The seam
translates Description → ComponentResource host-side.

**★ DECIDED — the mediation seam (`unitrepo-pulumi`) is an ACL with TWO EXPLICIT PORTS (hexagonal):**
NORTH-BOUND port = faces the OSGi domain, pure domain vocabulary, accepts `ResourceDescription`, ZERO
Pulumi types — lives in the bundle (or `unitrepo-handler-api`, the already-born-bundle SPI). SOUTH-BOUND
port = faces the engine, the narrow ~7-method gRPC façade (`registerResource`, `registerResourceOutputs`,
`readResource`/`invoke` — the surface already inventoried vs Pulumi SDK 1.28.0) — lives host-side. The
bundle sees ONLY north; the host implements ONLY south; the seam is the sole point knowing both. Same
shape as the `UnitHandler`/`osgi.extender` SPI already started.

**★ KEY SEQUENCING INSIGHT — STEP 1 IS the trigger for splitting `manifests`.** Question "manifests = 1
or 2 bundles?" was parked as "depends on a future engine". The figures showed the real answer: NOT a
hypothetical 2nd synthesis backend (helm/kustomize — no signal the user wants it). The trigger is STEP 1
itself: putting the resolver in PRODUCTION gives `manifests-model` (units/domains/catalog) a 2nd real
consumer (resolver + synthesis), maturing the rule-of-three case to split. ⇒ **DECISION leaning: keep
manifests as ONE bundle for now (splitting now = speculative per [[rke2lab:refactor-pipeline-candidates]]
no-speculative-abstraction); STEP 1 will empirically reveal the model surface the resolver consumes, and
THAT justifies the split.** Reinforces the agreed order: STEP 1 first, it informs STEP 2's carving.

**STILL OPEN for STEP 2 (not yet brainstormed):** where exactly to cut seed-master (`bdd-core` 58 vs
`bdd-ledger` 10 — needs a domain interface between them); the exact `ResourceDescription` record shape;
netplan re-carve (`/api` vs impl/CLI). Migration order (leaves-first): 1 systemd-contract pilot → 2
netplan+cdk8s-systemd → 3 manifests (= STEP 1, retire walker) → 4 unitrepo-core/-handler (born bundles)
→ 5 host+mediation seam (THE gated spike). Steps 1–4 additive/risk-free; step 5 gated on the passed spike.

## ★★ RECRUIT-A-SPECIALIST = DECLARATIVE recruitment (v2 target, brainstormed 2026-06-16)

User asked: when a NEW specialist appears in the universe answering a new seed-master need, how does
seed-master recruit it? **The shape the user proposed is correct** (another node publishes a new
doctor-specialist type → seed-master instantiates it in its unit graph and calls its services), with
THREE precisions, all grounded in the model's own prior decisions:

- **PULL not push (serve-own-DAG).** The user's "makes it visible/available to others" is the one word
  to correct: node B does NOT push to seed-master. Node B **serves its own DAG**; seed-master **pulls**
  the closure (≈ git clone) WHEN its need demands. Visibility = reachability via ownership replication,
  no broker/registry/push. This is the P2P thesis vs p2's central repo.
- **Node homogeneity, not "another application".** Node B (domain=doctor-specialists) and seed-master
  (domain=provisioning) are PEER NODES (the homogeneity axiom: same kind, differ by domain). The
  "shared docstore competence" the user named IS the unitrepo itself — they speak the same capability
  vocabulary, which is what makes recruit work without coupling.
- **"Call its services" has gates.** resolve (wire provider↔need) = PROVEN; **uses: coherence** = the
  resolver REFUSES an incoherent closure and narrates why (the recruit safety guarantee); **load** the
  behavior via the extender `UnitHandler` = v2-deferred (framework-move); and for an ACCUMULATING
  specialist (drift/intervention, NOT the reactive DbusTcp/Network) the recruit = a **StackReference
  edge to its ledger stack** ([[specialist-as-ledger-northstar]]), "calling its services" = folding its
  ledger history.

**★ HOW A NEED IS DECLARED — it EMERGES from a Referral, the operator codifies it (the user's real
question).** The need is NOT hand-coded. Mechanism (the user's framing, confirmed): need is named
**by-symptom**, where a **domain** defines the baseline of treatable symptoms and a **specialist
inherits-then-adds/removes** (the same override model already applied to specialists) — expressed as a
capability: specialist `provide handles-symptom=X`, need `require handles-symptom=X`. The TYPE-vs-symptom
mix the user wants = exactly this inherit-with-override pattern. **The new need's SOURCE = an unmet
`Referral`:** a symptom appears at runtime → the current specialist can't treat it → it emits a
`Referral` = unmet-need-with-why (`ReferralReply`/`Assessment`) → THAT unmet referral IS the new need.
The **operator** then promotes the ad-hoc observed referral into a durable codified `Require-Capability`
(the ad-hoc→codified gradient of [[doctor-remediation-model]]). Then the recruit loop closes:
codified need → a new specialist unit appears providing `handles-symptom` → seed-master pulls its
closure → resolver wires it. Ties to the atlas deferred edge `grant-seam-awaits-referrals` (the Referral
is already the intended trigger). Already half-built in the shipped doctor (`Referral`/`ReferralReply`/
`Assessment` exist — [[referral-roundtrip-state]]).

**STILL OPEN on recruit (deferred):** Referral → grant-seam (GrantPolicy) wiring; the STATIC→RUNTIME
frontier (today the universe is known at construction; a unit appearing AT RUNTIME needs live
re-resolution = the deferred hot-swap edge, rewire-before-prune).

**★ RECRUITABILITY IS A 2ND DECOMPOSITION CRITERION (user insight 2026-06-16).** Until now the
decomposition line had ONE criterion: gRPC coupling (bundle vs host). The user identified a SECOND that
sets the GRAIN of units INSIDE the bundle layer: **recruitability**. For a specialist unit to be
recruitable it must be SELF-CONTAINED — declare its own Provide (`handles-symptom`) and its own Require
(what it needs), limited to ONLY what concerns it, no leak into a neighbor. The anti-pattern it forbids:
a unit that reaches a neighbor through UNDECLARED coupling (not in its Require) — the resolver can't wire
what it can't see, so the recruit fails and the boundary is proven wrong. **★ THE PAYOFF — the criterion
is SELF-VERIFYING: the just-merged resolver is the DECOMPOSITION ORACLE.** Express a candidate split as
each unit's Provide/Require, run resolve; a missing Require or an incoherent `uses:` closure → the
resolver REFUSES → the frontier is wrong, proven not judged. This is the thesis's `uses:`-coherence
applied to decomposition itself. **SEQUENCING CONSEQUENCE (the wisdom point, user asked which path is
safest):** the oracle exists IN PRODUCTION only AFTER step 1 (which promotes the universe-builder
test→`src/main`). So designing step-2 splits NOW would be hand-judging without the oracle — the very
subjectivity the criterion replaces. ⇒ **STEP 1 FORGES THE TOOL THAT VALIDATES STEP 2.** Do step 1
first; then step 2 has a real executable oracle (express candidate Provide/Require → resolve → refusals
prove bad frontiers). Same empirical-trigger logic already accepted for the manifests split. The open
`bdd-core` vs `bdd-ledger` cut is the first case to run through this oracle once it is in prod.

**NEXT (user picked "capture then finish step 1"):** resolve the STEP-1 spec's open root-of-resolution
ambiguity (the production universe = the FINE layer only — domains+units resolved from a root that
`requireAll`s all domains, i.e. the walker's exact scope; NOT the module layer, which would drag Maven
module→module edges synthesis doesn't need), then self-review + submit the spec
`wip/specs/2026-06-16-walker-retirement-design.md` (drafted on branch
`design/step1-walker-retirement-spec`, base origin/main 7739d154, NOT yet committed). Preview-driven
throughout, per [[decision-options-in-preview]] + [[diagram-preview-file]] (`.claude/claude-preview.adoc`,
kroki-safe + en-US + faithful vocabulary).

## Atlas

`docs/architecture/integration-atlas.adoc` is a first-class living view (2nd adopter of the
rke2lab monotone-additivity ritual). unitrepo GENERALIZES the doctor's record substrate and
realizes its two open deferred edges: recruit-a-specialist ← resolve-and-pull; grant-seam-
awaits-referrals ← lens-visibility. HealthSystem drawn once as shipped base.

## ★ ATLAS REFRESHED 2026-06-14 (commit 0bc87bf on docrepo-dag-wip main) — reframe CONFIRMED

Reopened the atlas before the rke2lab absorption (the ritual's cross-link rule). Verdict: HOLDS,
but the doctor baseline had MOVED — **intervention-provenance SHIPPED to origin/main 7e5ec7d1**
(DriftSpecialist + InterventionLedger + StackCoordinate, 260 tests). Verified code-faithful (all
classes on origin/main, controlplane/bdd/). The convergence: the hub **[[specialist-as-ledger-northstar]]**
(a knowledge-accumulating specialist IS a ledger — memory = a dedicated Pulumi stack, consult =
fold its history, recruit = a StackReference edge, clinic = a graph of specialist-stacks) is the
UNITREPO SHAPE reached INDEPENDENTLY from the doctor side. ⇒ **★ user reframe (this session):
unitrepo is the GENERALIZATION of the shipped ledger-backed-specialist pattern** — not just a 2nd
subsystem beside the doctor. Corroborated, not asserted: a 2nd instance of stack-as-unit
(MedicalRecord AND InterventionLedger = both stacks folded over history = rule-of-three signal).
Additive refresh (monotone vs the GROWN baseline): DriftSpecialist/InterventionLedger added blue
to the diagram; recruit-a-specialist edge now PART-BLUE (first codified rung shipped) + resolve-
and-pull realizes its FEDERATED rung; 2 CONVERGENCE rows in the term table; verdict rewritten.
SCOPE GUARD (from the hub note): convergence holds for the ACCUMULATING specialist subclass only
(drift/intervention — domain outside seed-master's self-observation), NOT the stateless reactive
ones (DbusTcp/Network/Cluster); one concrete instance, no premature LedgerBackedSpecialist
abstraction. See [[model-substrate-alignment]] (per-resource+history-fold is THE journal mechanism,
the twin the unitrepo ContentStore mirrors).
