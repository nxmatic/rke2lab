---
name: per-domain-osgi-fakes-chantier
description: "Chantier (opened 2026-07-07, NOT started): give every OSGi domain a well-defined fake fragment so BDD stage tests resolve fakes from the real registry via the (variant=fake) selector — killing hand-made host-side stubs. Runs in its own worktree off the Task-8 stable commit. Re-enables NestedRunbookTest."
metadata:
  type: project
---

**The chantier (user's proposal, 2026-07-07):** "il faut qu'on migre tous les domaines dans le monde
OSGi, avec des fakes bien définis." Every OSGi domain should ship a **fake fragment** — a
`@Component` published into the registry under `type=fixture` + `variant=fake` +
`service.ranking:Integer=-1000` — so a BDD stage test resolves the fake through the connection's
`(variant=fake)` selector (`OsgiConnection.serviceSelector()`), agnostic, from the REAL registry. No
hand-made host-side stub (`StubConnection`) anymore.

**Why this is the right target (not scaffolding):** proven live in `SystemdAdapterStageTest` — the
`dbus-systemd-edge-fake` + `doctor-core-fake` fragments attach to their hosts, SCR wires them, the
selector resolves them, the negative ranking guarantees a nude `awaitService` never picks a fake by
default. It IS the pattern the user set from the start ("chaque host fournit un fragment de test").
Generalised across domains it gives a uniform, reusable fake base and removes the two problems a
lightweight `Scenario.create` harness hit: it forces driving through the real launcher+Felix (so
jGiven's cross-stage interception is genuine) AND serves collaborators from the registry (no stub).

**Inventory (2026-07-07):** OSGi domains = doctor, cluster, systemd(dbus), manifests, netplan,
unitrepo, ssh-to-age. Shipped fakes: `doctor-core-fake`, `dbus-systemd-edge-fake`. MISSING (~5):
cluster, manifests, netplan, unitrepo, ssh-to-age. Each = a fragment module (bnd `Fragment-Host` +
`Provide-Capability: io.nxmatic.rke2lab.embed; type=fixture; suite=<domain>; role=<...>-fake`) + fake
`@Component`s. This is the concrete first move of the long-signalled
[[osgi-frontier-underpopulated-chantier]].

**FIRST consumer + acceptance test — re-enable `NestedRunbookTest`:** it is `@Disabled` (see
[[cluster-seed-execution-state]]) because it is the ONLY coverage of the cluster-readiness FAILURE
path (FAILED checkpoint + doctor consult + targeted runbook) and its `Scenario.create` harness can't
reproduce jGiven interception. Rebuild it on launcher+Felix with a new `cluster-core-fake` fragment
(mirror `SystemdAdapterStageTest`); the assertions already written in the disabled file are the spec.

**Operating model:** run in its OWN external worktree off the Task-8 stable commit (per the
workspace-isolation rule). BDD-pipeline migration of the other 6 pipelines is a SEPARATE worktree /
chantier ([[bdd-pipeline-migration-plan]]) — the user sequences them independently.

See [[osgi-connection-service-selector]] [[cluster-seed-execution-state]] [[fragment-contribution-mediation-model]].
