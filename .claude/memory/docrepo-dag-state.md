---
name: docrepo-dag-state
description: "docrepo-dag-wip — a NEW repo: an OSGi-resolution + P2P-provisioning UNIT REPOSITORY (git-like DAG, self-hosting). MODEL COMPLETE; V1 SPEC WRITTEN + restructured (per-concern figure docs, empty-root history, .wip convention). Option-B: a Unit IS a git tree, ContentStore on JGit (= lost system's own path). TERMINOLOGY: document→Unit (the bundle-equivalent; config+logic=data+behaviour). NEXT = absorb into rke2lab (archive branch + feature branch + 3 modules) in a FRESH session, THEN writing-plans."
metadata: 
  node_type: memory
  type: project
  originSessionId: fad25661-6d06-4825-8ce2-6e3bbdbbafd5
---

A NEW standalone repo **`/private/var/lib/git/nxmatic/docrepo-dag-wip`** (local only, no
remote; working name, rename later). Reconstructs a design the user lost. Substrate under the
user's existing federated OSGi system ([[serviceloader-specialist-spi]]). Generalizes the
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

## Atlas

`docs/architecture/integration-atlas.adoc` is a first-class living view (2nd adopter of the
rke2lab monotone-additivity ritual). docrepo GENERALIZES the doctor's record substrate and
realizes its two open deferred edges: recruit-a-specialist ← resolve-and-pull; grant-seam-
awaits-referrals ← lens-visibility. HealthSystem drawn once as shipped base.
