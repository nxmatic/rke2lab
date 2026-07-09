---
name: foundations-before-domain-migration
description: "Load-bearing sequencing principle (user, 2026-07-09): reach the TARGET on foundations + runtime FIRST, then attack the business/domain migration. Mixing the two was the flaw — every cluster attempt hit a foundation gap mid-domain-migration. Two strata, not one continuum."
metadata:
  type: feedback
---

**User insight (2026-07-09):** "il faut travailler sur les fondations et le runtime, arriver à notre
cible, avant de s'attaquer à la partie métier. c'est ce qui nous manquait dans notre approche — on
mélangeait les deux."

**Why it matters:** the seed-broker / BDD migration kept feeling problematic on cluster because we
attacked a DOMAIN (cluster) while its FOUNDATION was still incomplete — so we hit plumbing gaps
(jGiven two-path, pipeline-port's three conflated natures, the verifier's host anchors) in the middle
of business work, and the two concerns contaminated each other. The fix is to STRATIFY:

- **Strate 1 — foundations + runtime → reach target FIRST.** scenario-engine complete (broker + graft
  SHIPPED); jGiven reduced to ONE path (kill the redundant pipeline-jgiven wrap — scenario-engine
  already provides jGiven at runtime-scope, the system capability); synthesis-port extracted
  (Topic.Execution out of pipeline-port); pipeline-port reduced to only what dies WITH the seed
  (Topic.Checkpoint/Pipeline). Plus the domain-agnostic verifier cuts (see cluster scan below,
  fibers 1-5). All mechanical, each removes one import, independently safe.
- **Strate 2 — the business/domain migration.** cluster then the other domains, RESTING on a stable
  foundation. Only fibers 6-7 below. Never started until strate 1 reaches target.

**How to apply:** before touching a domain migration, ask "is the foundation it rests on at target?"
If not, finish the foundation first. Do NOT interleave a foundation cut with a domain cut in the same
increment. This supersedes the plan's ordering where cluster (chantier 2) came right after the graft —
the pipeline/jGiven/synthesis foundation work now comes BEFORE cluster. See
[[pipeline-migration-strategy-revised]] [[pipeline-modules-destined-to-disappear]].

## jGiven two-path (the first foundation cut, user-chosen 2026-07-09)

jGiven reaches modules by TWO paths — the redundancy to kill:
- `pipeline-jgiven` (BSN io.nxmatic.rke2lab.jgiven.wrap): wraps jgiven-core+junit5 2.0.3 as an OSGi
  bundle, `Export-Package: com.tngtech.jgiven.*`. Test-scope wrap.
- `scenario-engine`: carries jgiven-junit5 at runtime-scope (the dogfooding promotion) — the system
  capability now.

Measured dependents (2026-07-09):
- take BOTH pipeline-jgiven AND scenario-engine: `cluster-bdd`, `pipeline-testkit`.
- take pipeline-jgiven only: `doctor-core-test`, `manifests-core-test`.
- take raw jgiven-core/junit5: `seed-master` (+ scenario-engine), `pipeline-probe-test`.
- pipeline-port itself does NOT depend on jGiven (domain-annotations + slf4j only) — the port is clean;
  jGiven lives in the sibling pipeline-jgiven/testkit/probe modules.
Target: modules get jGiven via scenario-engine (the one system capability); delete pipeline-jgiven.
`Topic.Checkpoint` (the jGiven-playing nature) has ONLY ONE prod user: `ClusterSeedTopic` (seed-master)
— so Checkpoint dies WITH the cluster migration, it is not a separate prep step.

## CLUSTER SCAN (2026-07-09) — the tangle mapped, the 7 fibers to cut

The cluster domain (osgi/domains/cluster/*) is ALREADY Pulumi-blind (grep com.pulumi = 0). The blocker
is NOT Pulumi — it is `ClusterBootstrapReadinessVerifier` (seed-master
.../controlplane/readiness/) FUSING pure phase reasoning with three host anchors, plus a duplicated
scenario the host still needs for live orchestration.

Cluster modules: cluster-port (5 files, the seam: `ClusterReadinessContact` {isApiReady,
areControllersEffective}, `ControllerRef`, `ClusterReadinessPhase`, `ClusterSchemaRef`); cluster-core
(3, `ClusterSpecialist` @Component — doctor-facing reasoning, NOT readiness; NO readiness verifier
today); cluster-bdd (4, the migrated in-container `ClusterReadinessScenario` + `ClusterBddScenarios`
front-door/graft membrane); cluster-edge (3, `KubectlClusterContact`).

Host-side (seed-master): only `ClusterReadinessResource` + `ReadinessOutputMapper` import com.pulumi
(stay host). `ClusterBootstrapReadinessVerifier` imports cluster.port but NO pulumi — pure reasoning
stranded host, the prime candidate. Its anchors, classified:
- pure/movable: `ClusterReadinessContact`, `ControllerRef`, the NIO kubeconfig poll.
- soft anchor: `SeedLog` (per-phase checks already take Consumer<String> logger; drop the fallback).
- BLOCKS the move: `ControlplanePolicy` (requiredControllers() hard-codes the controller catalog —
  kube-vip/cilium/kdns/openebs/headscale — against a host policy type: THE hardest fiber);
  `SeedNodeBootstrapWatcher` + `SeedSystemdAdapterRuntimeStatusSnapshot` (checkKubeconfigPublished
  fuses the systemd-bootstrap gate — host, systemd domain's concern); `BootstrapConfig` (fat host
  record; verifier reads readinessTimeout/kubeconfigRef/nodeName/incusProject — narrow to a small
  port-owned input).

`VerificationResult` = a nested record in the verifier (8 flat fields + asOutputs()). Produced by the
verifier factories, consumed by `ClusterReadinessResource`/`ReadinessOutputMapper` (pulumi) +
ResourceCreationPipeline/ResourceManager. Does NOT cross the seam — host-only projected verdict, stays
host as the Pulumi-facing output contract.

Two `ClusterReadinessScenario`: cluster-bdd's is the migrated "après" (self-contained, plays
in-container, consults the doctor itself, returns RunbookEnvelope) — SUPERSEDES the seed-master thin
"avant" (a DSL skeleton delegating to LiveClusterReadinessProbe→verifier). The 3 gaps blocking deletion
of the avant: (1) the systemd-adapter dependency edge (host phase-0 gate; cluster-bdd's KUBECONFIG
check is trivial kubeconfig!=null); (2) the policy→controllers projection (host passes real refs;
cluster-bdd uses List.of() = vacuously effective); (3) VerificationResult production + graph handoff
(cluster-bdd emits RunbookEnvelope, no VerificationResult).

**The 7 fibers, in safe cut order (1-5 = strate-1 foundation, mechanical, each drops one import; 6-7 =
strate-2 business, depend on the graft being wired into the host pipeline):**
1. Cut SeedLog from the verifier (inject logger only).
2. Split the kubeconfig-published phase from the systemd-bootstrap gate (systemd gate stays host).
3. Move the policy→List<ControllerRef> projection OUT of the verifier (host projects, domain reasons
   over refs). THE load-bearing cut — controller-catalog knowledge is what says "this is domain."
4. Narrow BootstrapConfig to a small port-owned readiness input {kubeconfigPath, timeout, nodeName}.
5. Relocate the now-pure verifier reasoning into cluster-core; VerificationResult stays host.
6. Migrate the 3 live gaps into cluster-bdd's scenario (systemd ordering, real controller set, wait
   loops) to reach live parity.
7. Delete the host avant (bdd/ClusterReadinessScenario/Stage/Probe/Live+Simulated) once the host
   consumes the cluster-bdd graft (ClusterBddScenarios.run()) instead — keep only the pulumi-irreducible
   ClusterReadinessResource + ReadinessOutputMapper + VerificationResult + broker sow/graft. Today the
   graft is exercised only by ClusterReadinessScenarioInContainerTest, not by ResourceCreationPipeline.
