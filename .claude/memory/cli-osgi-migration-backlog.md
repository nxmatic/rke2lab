---
name: cli-osgi-migration-backlog
description: "BACKLOG after R4 — the two standalone CLIs (manifests-cli, netplan-cli) still ServiceLoader.load our ports + shade their model bundle FLAT, so since WI-C0 they CANNOT synthesize (the @Reference Resolver is null off-framework). R4 migrated only seed-master (the Pulumi entrypoint); the CLIs are the SECOND use-case that proves the boot-seam model GENERALISES across entrypoints. Their embed-set DIFFERS per CLI (manifests-cli needs manifests-core, netplan-cli needs netplan-core) — so this is not a copy-paste of seed-master's boot; it tests that OsgiRuntime parameterises by entrypoint. NOT done in R4."
metadata:
  node_type: memory
  type: project
---

## What's broken (and why it matters)

After R4, `seed-master` boots Felix and consumes the registry — but the two CLIs did NOT follow:

- `manifests-cli` (`Main.java`): `ServiceLoader.load(ManifestSynthesisService.class)`.
- `netplan-cli` (`SynthesisCommand.java`): `ServiceLoader.load(NetplanSynthesisService.class)`.

Both shade their model bundle FLAT (the same "THE KNOT" R4 solved for seed-master: ServicesResourceTransformer
fuses META-INF/services, ManifestResourceTransformer drowns OSGI-INF). Since WI-C0 service-ified the
Resolver (`@Reference Resolver`, null off-framework), synthesis CANNOT run flat — so as shipped these CLIs
are broken for synthesis. This is honestly recorded, not hidden; it is the price of R4 stopping at
seed-master.

## Why this is the real generality test of the model (user's point, 2026-06-20)

R4 proved the boot-seam on ONE use-case (the Pulumi entrypoint). The CLIs are a SECOND, structurally
different use-case: standalone `main()` entrypoints. The model only EARNS "it generalises" once it holds
here too. And the embed-set is NOT the same per entrypoint:

- `manifests-cli` needs `manifests-core` (+ the boot stack) embedded.
- `netplan-cli` needs `netplan-core` (+ the boot stack) embedded — a bundle seed-master does NOT embed.

So migrating the CLIs is not copy-paste: it exercises `OsgiRuntime` being PARAMETERISED by which bundles
an entrypoint declares (the derived `system.packages.extra` already supports this — each entrypoint lists
only ITS bundles, [[osgi-runtime-r4-boot-seam-state]] §boot). That is exactly the robustness check the
single-entrypoint R4 could not give.

## The work (each its own commit, its own slice)

1. Make each CLI boot Felix like seed-master: embed its model bundle(s) + the boot stack under
   `META-INF/bundles/`, stop shading them flat, build `OsgiRuntime` with that entrypoint's bundle set,
   consume the service via `awaitService` (single path — no ServiceLoader, per [[dual-path-inline-until-r5]]).
2. THEN delete the 8 orphaned `META-INF/services` files on our ports (kept alive only for the flat CLIs):
   the manifests-port SPIs + `netplan.api.NetplanSynthesisService`. With no flat caller left, this is the
   R5 ServiceLoader retirement for the CLI half.
3. Re-verify: each CLI synthesizes under its embedded Felix (a CLI-scope test booting the framework, like
   `HostSeamEmbeddedFelixTest`); count surefire reports.

## Sequencing

A standalone increment after R4 merges — NOT on any critical path, but it is what lets us claim the
boot-seam model holds across entrypoints (and it unblocks the full R5 ServiceLoader deletion). Pairs with
the post-merge memory-gardening ([[memory-synthesis-prune-the-how]]) and the worktree-provisioning
automation ([[worktree-provisioning-handoff]]) as the R4 tail.

See [[osgi-runtime-r4-boot-seam-state]] (the model this generalises) [[dual-path-inline-until-r5]]
(awaitService single path; the CLI ServiceLoader is the last surviving fork) [[osgi-runtime-migration-state]]
(R5 retires ServiceLoader).
