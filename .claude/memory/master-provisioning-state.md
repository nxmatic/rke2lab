---
name: master-provisioning-state
description: "Where master (seed-master) provisioning stands — config.yaml.d fixed, now blocked on systemd-adapter dbus runtime probe"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

State of seed-master bootstrap convergence as of the pulumi run logged at 2026-06-04 00:46 (`/private/tmp/pulumi.log`).

**Progress:** The `config.yaml.d` bug is FIXED and no longer blocks — preview at 00:44 shows verbatim `.yaml` fragments (commit a79f2cb0). Inside the systemd-adapter status the master is largely healthy: `rke2lab.target` active, `mandatoryTargetHealthy=true`, `failedUnits=0`, `pendingJobs=0`.

**Remaining blocker (the "1 errored" / exit 32):** the pipeline's **systemd adapter runtime probe** fails:
- `[readiness] instance master ... not reachable yet via incus exec; Instance not found`
- `systemd dbus probe failed at tcp:host=bioskop-master,port=12434: Connection refused`
- fails after PT1M → `PipelineStageFailure: bootstrap: systemd adapter` at `SystemdAdapterStage.launch(SystemdAdapterStage.java:31)`

So: master node converges internally, but the control-plane-side **dbus-over-TCP bridge (port 12434)** the SystemdAdapter probes is not reachable — either the `rke2lab-dbus-tcp-system-bus.sh` unit isn't up, the incus instance "master" isn't resolvable by name at probe time, or a timing/ordering gap. This is the next thing to debug when we return to provisioning.

**Scope decision (2026-06-04):** the user wants to RESTRICT what master deploys to only what's needed to reach vCluster provisioning. Too many apps deploy today; trim to the Layer-1 minimum (per [[seed-vcluster]] plan: drop Tekton/CI-CD, CertManager, Envoy Gateway, FluxInstance CR, peer cloud-config → deferred to the Flux/Layer-3 regime). Where the trimmed apps ultimately live is decided later, when we get there. This both shrinks the bootstrap surface and removes non-essential failure sources while debugging convergence.

seed-vcluster work stays FROZEN until master is Ready (see [[seed-vcluster]]). Currently PARKED to return to [[bdd-jgiven-test-strategy]].
