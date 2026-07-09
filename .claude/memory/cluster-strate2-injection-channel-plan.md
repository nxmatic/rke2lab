---
name: cluster-strate2-injection-channel-plan
description: "Plan for cluster strate-2 (fibers 6-7): cluster-bdd's in-container scenario has NO input channel yet — ClusterBddScenarios.run() takes zero params, hardcodes kubeconfig + empty controllers. Fiber 7 (the channel) must come BEFORE fiber 6 (the real logic): port the host's HostSeeder store→DAG→@ExpectedScenarioState pattern into cluster-bdd, and make the poll a provided-state (user idea)."
metadata:
  type: project
---

**Where strate-1 left it (2026-07-09):** the host verifier `ClusterBootstrapReadinessVerifier` is
cluster-port-only (fibers 1-4 shipped). The descent (fibers 5→6→7) is strate-2. This plan is the
result of scanning the actual cross-world wiring before coding — decide on concrete, not projection.
See [[foundations-before-domain-migration]].

## The blocker the scan found: cluster-bdd has NO input channel

`ClusterBddScenarios.run()` (the in-container front-door) takes ZERO parameters. The scenario
(`ClusterReadinessScenario`) resolves its contact from the bundle registry, and HARDCODES the rest —
`kubeconfig() = Path.of("/srv/host/kubeconfig")` (fixed marker), `controllers = List.of()` (empty →
vacuously ready). So the host cannot pass `ReadinessInput` + the projected `List<ControllerRef>` in.
The scenario is MORE PRIMITIVE than the seed host-side path, which already has a full injection channel.

## The pattern to port: HostSeeder (host-side, already shipped for the seed)

Host-side, `ClusterSeedTopic` injects into its in-container scenario via `HostSeeder`
(seed-master/controlplane/bdd/): the driver seeds the JUnit session store (keys under `HostSeeder.NS`:
HOST_FACTS, CONNECTION, PROBES, CLUSTER_PROBE, RUN_MODEL, OUTPUTS_SINK); `HostSeeder`
(TestInstancePostProcessor, declared BEFORE JGivenExtension) reads the store and pushes ONE
`StageContext` carrier into the value-DAG via `executor.readScenarioState(carrier)`; the stages resolve
their `@ExpectedScenarioState` from it. RUN_MODEL/OUTPUTS_SINK use the inject-the-holder idiom (driver
holds an AtomicReference, run fills it, driver reads — no harvest-back, no static). This is the typed
host→in-container channel; cluster-bdd needs the same, scaled to its own inputs.

## The plan — fiber 7 (channel) BEFORE fiber 6 (logic)

**Fiber 7a — give cluster-bdd an input channel (the HostSeeder twin).**
- `ClusterBddScenarios.run(...)` takes the inputs (a `ReadinessInput` + the projected
  `List<ControllerRef>` + a poll policy — see below) and seeds them into the launcher session store.
- A cluster-bdd inbound extension (the HostSeeder twin) reads the store → pushes a carrier into the
  DAG → the scenario's `Given`/`When` read `ReadinessInput`/`controllers`/`PollPolicy` as
  `@ExpectedScenarioState` instead of hardcoding them. Today `ClusterReadinessScenario` already uses
  `@ProvidedScenarioState`/`@ExpectedScenarioState` for kubeconfig/controllers/contact — the change is
  WHERE they come from (seeded, not hardcoded).
- Cross-realm caveat: the inputs must cross INTO the in-container world. `ReadinessInput`
  (kubeconfigPath, timeout) and `ControllerRef` are cluster-port types (system-exported seam), so they
  cross live — unlike the runbook coming back, which must be JSON (already handled by RunbookEnvelope).
  Confirm the seam export makes them safe to seed live; if not, they cross as a Document.

**The poll as a PROVIDED-STATE (user idea, 2026-07-09).** Rather than bury wait loops in the scenario
(against the BDD-as-engine design — the temporal poll is meant to be a Jupiter EXTENSION, the spike's
`PollUntilReadyExtension`) OR leave them host-imperative: inject the poll POLICY as one more field of
the seeded carrier. The host provides "poll with retry R until deadline D" as a provided-state; the
scenario/extension consumes it and applies it around the point-in-time contact calls. The scenario
stays point-in-time (its virtue); the WAITING is an injected concern, not scenario code. This is the
live form of `PollUntilReadyExtension` reached through the store→DAG channel.

**Fiber 6 — fill the 3 live gaps (needs 7a's channel first).**
Once the scenario receives real inputs, bring the host verifier's real logic in:
1. kubeconfig: replace `!= null` with the rich NIO check (exists, non-empty, `apiVersion:`+`clusters:`)
   — the logic is in the host verifier's `waitForKubeconfigPublished` body; the point-in-time half is
   what descends (the wait half is the injected poll policy).
2. controllers: use the seeded projected `List<ControllerRef>` (not `List.of()`); the vacuous-empty
   path stays valid when none are required.
3. systemd ordering: the phase-0 systemd gate stays HOST (fiber 2 put it in LiveClusterReadinessProbe);
   the in-container scenario does NOT gain a systemd dep — the host runs the gate before sowing the
   readiness scenario. Confirm the ordering is preserved across the seam (host gate → then sow).

**Fiber 7b — wire the graft into the host pipeline + delete the avant.**
Today `ClusterBddScenarios.run()` is called ONLY by `ClusterReadinessScenarioInContainerTest`, never by
`ResourceCreationPipeline`. Wire the host to: project controllers (`RequiredControllers.from(policy)`),
build `ReadinessInput` from its config, run the systemd gate, then sow/run the cluster-bdd scenario and
`graftUnder` the returned runbook (ScenarioGraft). Then DELETE the host avant: the verifier's reasoning
half, `bdd/ClusterReadinessScenario`+`Stage`+`LiveClusterReadinessProbe`+`Simulated`. KEEP host: the
pulumi-irreducible `ClusterReadinessResource` + `ReadinessOutputMapper` + `VerificationResult` +
`RequiredControllers` (host projection) + the broker sow/graft. The verifier's `VerificationResult`
factories (ready/failed/skipped, already static, host-facing) stay; only its reasoning half descends.

## Open question to resolve when resuming
Does the readiness scenario cross as broker `sow` (execution twin, Document) or graft (observability
twin, runbook)? The scan shows cluster-bdd uses the GRAFT (`run()` → RunbookEnvelope JSON), NOT the
broker. The broker is for Document→Document verbs (readiness-verdict, consultation). So readiness stays
a graft; inputs go via the store channel (7a), NOT via a broker Document. Keep the two twins separate
(the spec mandates it). See [[systemd-gate-poll-to-listener]] (the gate's own poll→listener increment).
