---
name: osgi-runtime-r4-boot-seam-state
description: "DESIGN/CARTO for IMPL slice R4 (the architectural lift): boot Felix inside seed-master's Pulumi callback + wire the host consume-seam onto the registry. Read-only carto done 2026-06-19 on integration @4d5521e4 — NOT yet coded, NO worktree yet. ★ KEY FINDING: the seed-master exec-jar SHADES manifests-core/netplan FLAT (classes merged, ServicesResourceTransformer fuses META-INF/services, ManifestResourceTransformer rewrites ONE manifest → the bundles' OSGI-INF/Service-Component are drowned). And Pulumi.yaml launches the program via `binary:` = ONE exec-jar (not a -cp with a bundles/ dir). So Felix CANNOT installBundle() separate bundle entities in the deployed process — the R1-R3 reactor-classpath install pattern does NOT carry to the shaded deployment. This is THE knot R4 must solve. Proof path is pulumi PREVIEW (a dry-run, in-scope per CLAUDE.md — NOT -Plive up), which really boots Felix in the Pulumi callback without mutating master. Cap decision still settled: go to the runtime."
metadata:
  node_type: memory
  type: project
---

## Why this note exists

R4 is the architectural lift of the OSGi runtime migration (spec §5 + §4). Bigger and riskier than
R1-R3, so the user asked for a read-only carto pass BEFORE coding (the same discipline that caught the
manifests-core rename and the R3 dual-path). This note records what the carto found and the design
choices it forces; it becomes the R4 worktree's startup note once the packaging decision is taken.

## The proof path — pulumi PREVIEW, not -Plive up (user reframing, 2026-06-19)

The spec gated R4's final proof on `-Plive` (real `pulumi up` against master). The user reframed: a
`pulumi preview` (dry-run) REALLY boots Felix inside the Pulumi callback, runs SCR, exercises the host
consume-seam over gRPC to the engine — WITHOUT mutating master. CLAUDE.md explicitly allows `pulumi
preview` directly ("dry-runs like pulumi preview are fine to run"). So R4 validates in two in-scope
steps: (1) embedded-Felix host-scope test (seam wires + resolves, like R1-R3); (2) a real `pulumi
preview` (the boot in the actual Pulumi callback). The real `pulumi up` (provision/update master) stays
the USER's gesture — now an optional final confirmation, no longer a slice prerequisite. The `-Plive`
frontier dissolves; R4 need NOT be split into R4a/R4b.

## ★ THE KNOT — how do the osgi/ bundles exist as installable bundles in the deployed process?

Three grounded facts (integration @4d5521e4):

1. **seed-master shades the bundles FLAT.** `exec/seed-master/pom.xml` depends on `manifests-core`,
   `netplan`, `systemd-contract`, `unitrepo-core` and shades them into one `-exec.jar`. The shade
   `ServicesResourceTransformer` fuses all `META-INF/services`; the `ManifestResourceTransformer`
   writes a SINGLE MANIFEST.MF (host's). → the bundles' own `Service-Component:` headers and the
   per-bundle manifests are gone; `OSGI-INF/*.xml` classes are present but no longer a bundle entity.
2. **Pulumi launches ONE binary.** `Pulumi.yaml` → `runtime: java, options.binary:
   exec/seed-master/target/seed-master-0.1.0-SNAPSHOT-exec.jar`. The Pulumi Java runtime runs ONE jar,
   not a `-cp dir/*`. The release path (`nix run .#deploy`) swaps that `binary:` to the nix-store jar.
3. **The R1-R3 install pattern is reactor-only.** `FelixFrameworkExtension.install(artifact)` finds a
   bundle by scanning `java.class.path` for a jar/`target/classes` dir carrying a manifest — works in
   the reactor TEST classpath, but in the shaded exec-jar there is no separate bundle artifact to find.

So Felix-in-the-process cannot `installBundle(file:…)` a `manifests-core` bundle that no longer exists
as a discrete entity. **R4 must choose a packaging that gives the running process installable bundles.**

## The packaging options (to decide WITH the user before coding)

- **(A) Bundles as jars in a directory, NOT shaded.** Stop shading the osgi/ bundles into the exec-jar;
  ship them as discrete bundle jars in e.g. `bundles/` beside the seed-master jar (or inside it as
  resources, extracted at boot to a temp dir). The seed-master jar keeps only host + Felix + felix.scr;
  at boot it `installBundle(file:bundles/*.jar)`. Closest to a real OSGi deployment, preserves each
  bundle's manifest/OSGI-INF intact. COST: changes packaging — the shade stops pulling the bundles, the
  flake `seedMasterJar` derivation must also stage the bundle jars, and `Pulumi.yaml`'s single-`binary:`
  model must tolerate a sibling `bundles/` dir (the jar's launch dir is the repo root at preview, the
  store path at deploy — the bundle dir must resolve in both).
- **(B) Felix reads the flat classpath (no separate install).** Keep the flat shade; expose the app
  classpath to Felix so the @Components resolve from it. Felix can run with bundles backed by the system
  classloader, but the drowned `Service-Component`/`OSGI-INF` (fact 1) must be reconstructed — fragile,
  fights the shade transformers, and erodes bundle isolation (the whole point). NOT recommended on
  current read, but cheaper on packaging.
- **(C) Hybrid: an OSGi-aware exec assembly.** A launcher jar whose classpath is a set of intact bundle
  jars (e.g. bnd's `bnd run` / a felix launcher layout, or maven-bundle a runnable framework). Bigger
  build change; most "correct OSGi" but furthest from the current shade/flake/Pulumi.yaml machinery.

The carto's lean: **(A)** — it preserves the bnd-emitted bundle metadata that R1-R3 worked to produce,
and keeps gRPC on the flat host classloader (the bundles dir holds only pure model bundles, never gRPC).
But it touches the flake + Pulumi.yaml launch surface, which is runtime-adjacent config → the user must
weigh it. DO NOT code R4 until (A/B/C) is chosen.

## What is already known + easy (the non-knot parts)

- **Where Felix boots:** `ApplicationPipeline.run()` calls `Pulumi.run(ctx → body)` when
  `PULUMI_MONITOR` set (`ApplicationPipeline:39`). The composition root is inside that callback. Felix
  `init().start()` + install bundles + start felix.scr goes there, BEFORE the bootstrap pipeline runs.
- **The host seam (3 sites):** `IncusResourceBootstrap.singleSpiProvider(Class<T>)` (line 644) is
  called at :518 (ManifestSynthesisService) and :524 (ManifestExplodeService);
  `EntryGatePolicyEnforcer.enforceManifestUpdateGate` (:50) loads `ManifestUpdateGate` (the R3-deferred
  gate). All three move from `ServiceLoader` to `getServiceReference`/`getService` on the booted
  framework's BundleContext, typed via `system.packages.extra` exporting the API package from the system
  bundle (the bench/R1 single-exporter trick). `IncusResourceBootstrap` is built at `IncusStage:23`,
  inside the pipeline → it needs the BundleContext threaded in (constructor or a host-side Service
  accessor). `singleSpiProvider`'s "exactly one" check maps to a 1..1 registry lookup.
- **gRPC stays flat (invariant, do NOT break):** issue #1565 — gRPC transport discovery is a
  ServiceLoader on the TCCL that bundle isolation breaks; the `spike/osgi-grpc-host` test proved a
  TCCL-pinned host seam works. That spike is NOT in the tree (was a throwaway branch) — its LESSON is
  the constraint: no gRPC type ever crosses into a bundle; the seam reads model services only. The host
  (IncusResourceBootstrap/EntryGatePolicyEnforcer) must NOT become bundles.
- **Dual-path from R3:** `NodeEnvContributorRegistry.forServiceLoader()` + the framework-less `new`
  callers still work; R4 does not retire ServiceLoader (that's R5). R4 ADDS the booted-Felix consume
  path at the host seam.

## Validation (when coded)

- Embedded-Felix host-scope test (reuse osgi/testkit) proving the seam resolves the 3 services from a
  booted framework. Then a REAL `pulumi preview` from the repo root (flox) — boots Felix in the Pulumi
  callback, SCR publishes, the seam consumes, preview completes with no master mutation. COUNT surefire
  ([[build-verification-gotchas]]). The `pulumi up` confirmation is the user's, optional.

## Workspace / next step

- NO worktree yet — this is the design/carto output, sitting on integration @4d5521e4. Next: take the
  packaging decision (A/B/C) WITH the user, THEN spin the R4 worktree off design/target-module-layout
  with this note as its startup brief, and code there (the design session does design, not impl).
- The packaging choice touches the flake + Pulumi.yaml (runtime-adjacent) → user-owned decision, not
  standing-autonomy ([[standing-autonomy-except-runtime-config]]).

See [[osgi-runtime-migration-state]] (spec §4 is the runtime-target design this implements),
[[osgi-runtime-r3-consume-references-state]] (dual-path + the deferred gate), [[osgi-runtime-r1-scr-state]]
(embedded-Felix proof + typed-access trick), [[docrepo-dag-state]] (#1565 gRPC/TCCL),
[[model-substrate-alignment]] (OSGi describes, host actualises), [[merge-from-target-worktree]].
