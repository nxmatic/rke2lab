---
name: bdd-jgiven-test-strategy
description: "How the user wants tests built — JGiven BDD covering real module use-cases, DSL-first prototype before wiring to main"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

The user is starting a test discipline for rke2lab (near-zero coverage today: only `Cdk8sApiObjectResolverTest`, `DefaultManifestExplodeServiceTest`, and two netplan tests exist; tests are skipped by default via `.mvn`, run with `-DskipTests=false`).

**Preferences (durable):**
- Tests use **JGiven** (BDD Given/When/Then), not bare JUnit. JGiven is not yet wired anywhere — no dep in BOM/poms as of 2026-06-04.
- Cover **real use-cases of the modules first**, not isolated unit tests. The unit-level `DefaultManifestExplodeServiceTest` (commit fe213bf0) does NOT match this philosophy — revisit whether to keep it as a regression net or fold it into a use-case scenario.
- **GUIDING PRINCIPLE — tests are living documentation.** The user sees the test suite as the BEST way to show how the system behaves and what it's for — better than an operator or user manual, because it's executable and never goes stale. Design implication: Given/When/Then sentences must read as prose describing behavior, and JGiven's generated reports (HTML/AsciiDoc) ARE the deliverable "manual". Scenario naming, stage method `@As` text, and attached arguments should target a human reader, not just assertion coverage.

**Chosen approach (the user's plan):**
1. Design a **global plan** mapping entry points → expected outputs across ALL modules (not just manifests).
2. **Implement the JGiven scenarios first, NOT yet wired to the main modules** — stub/fake stages — to validate that the DSL reads well and captures the use-cases, before paying integration cost.
3. Only after the DSL feels right, wire stages to real module entry points.

**Entry-point map started (manifests module):**
- Point A: `ManifestSynthesisService.synthesize(req)` → manifests.k8s.yaml + systemd units dir + counts. ⚠️ DORMANT: `buildDomainRegistry` throws UnsupportedOperationException (layers→components migration); registrars exist but aren't wired. Verify in code, not just docs, before designing scenarios on it.
- Point B: `ManifestExplodeService.explode(req)` → `<domain>/<package>/<file>` tree by annotation. Healthy; site of the recent config.yaml.d bug.
- Real consumer chains A→B in `IncusResourceBootstrap` (seed-master) ~line 516-523.
- Context slices have easy factories: `BootstrapIdentity.unknown()`, `NetworkTopology.empty()`, `ComponentVersions.defaults()`, `ImageState.unknown()`.

**Full entry-point map (all modules, 2026-06-04):**
- **manifests** — `ManifestSynthesisService.synthesize` (A, dormant), `ManifestExplodeService.explode` (B, healthy), `Main` CLI. SPI-loaded.
- **netplan** — `NetplanSynthesisService.synthesize(req)` → NetplanSynthesisResult (pure derivation, no IO; cleanest BDD target). `NetplanCli` + BlueprintExportCommand/SynthesisCommand. Already has 2 plain-JUnit tests as style reference.
- **seed-master** — `controlplane.Main.main` drives the fluent `.during("bootstrap", b -> b.runBootstrapPipeline())` pipeline → Pulumi/Incus side effects. Hardest to test (external systems); needs heavy fakes.
- **cdk8s-systemd** — `SystemdChart(scope,id).synthesize(outdir)` → .service/.target files. Pure-ish (filesystem out).
- **systemd-contract** — api-only: `SystemdAdapterApiPaths`, `SystemdStatusSnapshot` (DTOs/contracts, little behavior).
- **sdks/incus** — generated Incus API client (low test value, it's generated).

**BDD-readiness ranking (best first):** netplan synthesize (pure) > manifests explode (fs-out, healthy, recently buggy) > cdk8s-systemd synthesize (fs-out) > manifests synthesize A (dormant) > seed-master pipeline (external systems).

**Layering is inter-module (decided 2026-06-04):** operator-level narrative scenarios live in `seed-master/src/test` (the orchestrator the operator actually runs), and later in each `seed-xxx`. Reusable *technical* stages live near the module they exercise (`manifests/src/test`: synthesize/explode). Layering follows the dependency direction (seed-master already depends on manifests). Two-tier JGiven: operator steps delegate to technical steps via `@NestedSteps` so the HTML report reads as an operator guide on top, technical detail underneath.

**Sharing technical stages — start with test-jar, expect to migrate.** Decision: begin with Maven `test-jar` (manifests publishes its stages, seed-master consumes scope=test). The user has bad experience with test-jar (same GAV as main jar + classifier; Maven mishandles transitive test-jar deps / classifier confusion). Accepted as a known risk to avoid creating a module prematurely; migrate to a dedicated `bdd-fixtures`/`test-support` module when the pain hits. Prototype starts in `manifests/src/test` (stages+scenarios together) before any extraction.

**Scenario language = English.** Per [[shared-artifacts-in-english]], JGiven scenario/step prose is living documentation = a shared artifact → write method names and `@As` text in en-US, not French.

**Design decisions locked in the 2026-06-04 brainstorm (ready to resume):**
- *Narrative level:* two-tier (operator narrative on top via `@NestedSteps`, reusable technical steps underneath).
- *Location:* prototype in `manifests/src/test`; target architecture puts operator scenarios in `seed-master/src/test` then each `seed-xxx`; technical stages near the module they exercise. Share via test-jar to start (migrate to bdd-fixtures module when it hurts).
- *First use-cases = a pair* proving the same technical stages serve a dormant and a live case:
  - **Scenario A — operator, synthesize()**: "master deploys only what vcluster provisioning needs" (= the trimming spec, executable). Chosen fake strategy: **call the real `synthesize()` and capture the current failure** — `buildDomainRegistry` throws UnsupportedOperationException. A is a red-expected scenario that documents reality AND becomes the engine that drives restoring buildDomainRegistry. (Not a hand-built tree, not a stub list.)
  - **Scenario B — operator, explode()**: "rke2-config fragments land where the installer can find them" — the config.yaml.d fix told as operator behavior. Branchable for real immediately.
- *Unit test fate:* **fold `DefaultManifestExplodeServiceTest` (fe213bf0) into Scenario B and delete it**; its 6 naming cases become parameterized JGiven `@Case` variants of B. (Matches "tests = use-cases" + no dead code.)
- *Still to do when resuming:* propose 2-3 JGiven wiring approaches (dep coords/versions in BOM), present remaining design sections, write spec to `docs/superpowers/specs/`, user review, then writing-plans.

**Bigger why (user's stated direction, 2026-06-04):** the user is tired of typing command lines and wants to move toward **automation with a natural-language-oriented interface**. BDD "tests = living manual" is a first step on that path (readable sentences that describe + verify behavior instead of commands to retype). Keep this north star in mind for future tooling choices, not just tests.

See [[working-style-narrate-progress]].
