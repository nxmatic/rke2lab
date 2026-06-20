---
name: cli-osgi-migration-carto
description: "CARTO (read-only on integration @717943b5, 2026-06-20) for applying the R4 boot-seam model to the OTHER exec/ modules — manifests-cli + netplan-cli, the 2nd/3rd entrypoints. FINDINGS: both still ServiceLoader.load our ports + shade flat → broken for synthesis since WI-C0. The boot is ALMOST entirely generic — seed-master's bootEmbeddedOsgiRuntime() differs from a CLI's only by WHICH model bundle(s) it embeds (the single .embeddedBundle(...) line); pax-logging + scr + resolver + the hasEmbeddedBundles guard are common. netplan-core IS a real bundle (@Component DefaultNetplanSynthesisService, BSN io.nxmatic.rke2lab.netplan.core, Export-Package) so it embeds exactly like manifests-core. So the model generalises; the increment is mostly mechanical + one shared extraction. Recommends: extract the common boot into a reusable entrypoint helper, migrate manifests-cli first (same bundle as seed-master), then netplan-cli (proves a NEW embed-set), then delete the 8 orphaned META-INF/services (R5-CLI)."
metadata:
  node_type: memory
  type: project
---

## Scope — the other exec/ modules (the generality test of the R4 model)

`exec/` has three executables: `seed-master` (migrated + proven at R4) and the two CLIs that did NOT
follow — `manifests-cli` (`Main.java`) and `netplan-cli` (`SynthesisCommand.java`). Both:

- `ServiceLoader.load(...)` our port service (manifests: `ManifestSynthesisService`; netplan:
  `NetplanSynthesisService`), and shade their model bundle FLAT (the same "knot" R4 solved for
  seed-master);
- are therefore BROKEN for synthesis since WI-C0 service-ified the Resolver (`@Reference Resolver` is
  null off-framework). Migrating them is both a FIX and the proof the boot-seam model generalises to
  standalone `main()` entrypoints, not just the Pulumi one.

## Finding 1 — the boot is almost entirely generic (one reusable extraction)

`seed-master`'s `BootstrapStage.bootEmbeddedOsgiRuntime()` is generic EXCEPT the model-bundle line:

```
OsgiRuntime.builder()
    .embeddedPaxLogging("pax-logging-api.jar", "pax-logging-logback.jar")  // common
    .withScr()                                                             // common
    .embeddedRuntimeJar("org.apache.felix.scr.jar")                        // common
    .embeddedRuntimeJar("org.apache.felix.resolver.jar")                   // common
    .embeddedBundle("manifests-core.jar")                                  // ← the ONLY per-entrypoint part
    .build().boot();
```

Plus the `hasEmbeddedBundles()` fail-fast guard (common). So the per-entrypoint variation is just the
SET of model bundles to embed. The clean shape: a small reusable helper (boot stack + guard) that each
entrypoint calls with its own `.embeddedBundle(...)` set — NOT copy-paste of the whole method into each
CLI. Candidate home: a static factory on `OsgiRuntime` (e.g. `OsgiRuntime.embeddedBootStack()` returning
a pre-loaded builder) or a tiny helper in osgi/runtime. Decide at slice start; keep it minimal (no
speculative abstraction — three call sites is the threshold, and we have exactly three entrypoints).

## Finding 2 — netplan-core is already a real bundle (embeds like manifests-core)

`netplan-core` has `@Component DefaultNetplanSynthesisService`, BSN `io.nxmatic.rke2lab.netplan.core`,
and an Export-Package — it is designed-for-OSGi, so it installs as an intact bundle exactly like
manifests-core. The per-entrypoint embed-sets:

| entrypoint | model bundle(s) to embed | boot stack |
|---|---|---|
| seed-master (done) | manifests-core | pax + scr + resolver |
| manifests-cli | manifests-core | same |
| netplan-cli | **netplan-core** (NEW — never embedded before) | same |

netplan-cli is the more interesting proof: it embeds a bundle seed-master never did, so it exercises
`OsgiRuntime`'s per-entrypoint `system.packages.extra` derivation on a fresh bundle set. (Watch: does
netplan-core pull any flat dep the host world must system-export? Its bnd `-noimportjava: true` +
Export-Package suggests a small surface — verify the derived exports resolve against the CLI's flat
classpath, the same fail-fast OsgiRuntime already does.)

## Finding 3 — netplan-cli still on `.api`, not `.port`

`SynthesisCommand` imports `io.nxmatic.rke2lab.netplan.api.*` (NetplanSynthesisService/Request/Result).
netplan kept `.api` (the contract→port rename touched the host-facing ports; netplan's service is
CLI-facing, [[api-extraction-tri-carto-state]] flagged the netplan `.api` split as mis-oriented). The
migration does NOT need to rename it — but note the seam type the CLI consumes is `netplan.api`, and
that package must be the single-exporter on the CLI's typed seam (netplan-core exports it; OsgiRuntime
must NOT also system-export it — the R1 rule, already enforced by the bundle-export subtraction).

## Recommended decomposition (to settle with the user)

1. **Extract the common boot stack** (reusable helper) — small, no behaviour change to seed-master
   (refactor its `bootEmbeddedOsgiRuntime` to use the helper, proving the extraction is faithful).
2. **manifests-cli first** — same bundle as seed-master, lowest risk; proves a 2nd (standalone) entrypoint
   boots. Embed manifests-core + boot stack; replace `ServiceLoader.load` with `awaitService` (single
   path, no fallback — [[dual-path-inline-until-r5]]); a CLI-scope embedded-Felix test.
3. **netplan-cli** — embed netplan-core (new embed-set); same seam swap; CLI-scope test.
4. **Delete the 8 orphaned `META-INF/services`** on our ports once NO flat caller remains — the CLI half
   of R5 ([[osgi-runtime-migration-state]]).

Per-commit, one worktree, squash at merge. Build + (optionally) a CLI run to prove synthesis; NO `-Plive`.
This is the increment [[cli-osgi-migration-backlog]] scoped — it can supersede that backlog note.

See [[cli-osgi-migration-backlog]] [[osgi-runtime-r4-boot-seam-state]] [[osgi-system-export-resolution-only]]
[[dual-path-inline-until-r5]] [[osgi-runtime-migration-state]] [[api-extraction-tri-carto-state]].
