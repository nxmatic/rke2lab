---
name: system-space-world-universe-glossary
description: "The precise vocabulary for the rke2lab decomposition, settled 2026-06-19. system = THE WHOLE (osgi+host worlds joined, running); space = a top-level LAYOUT dir (osgi/ host/ exec/, sorted by purity + nature axes); world = a RUNTIME classloader region (OSGi world = bundle loaders no gRPC; HOST world = flat loader holds gRPC); universe = RESERVED for the resolver (UnitResolver candidate set / ManifestsUniverse) — NOT a synonym for system. exec/ is the NATURE axis and its role is to MATERIALISE the system (seed-master embeds both worlds into one self-contained launchable jar). The triad: osgi DESCRIBES, host ACTUALISES, exec MATERIALISES. Lives in the atlas's 'two spaces' NOTE."
metadata:
  node_type: memory
  type: reference
---

## Why this glossary exists

We had used univers/monde/espace/axe loosely. The trigger: the atlas said "two spaces" (osgi/host) but
the layout had acted a third top-level dir `exec/` (the nature axis), and the user noted "exec is the
materialisation of the universes" + "the host/osgi worlds are PART OF a universe" — pointing at a real
gap: there was no word for THE WHOLE. Carto of actual usage (atlas + specs + code) fixed each term.

## The terms (each used precisely; do not blur)

- **system** — THE WHOLE: the two worlds (OSGi + host) joined into one thing that runs. The worlds/spaces
  are PARTS OF the system; the system is NOT a third space. (We already say it: "seed-master IS the
  system", "more deterministic system".) This is the word that was missing.
- **space** — a top-level LAYOUT directory: `osgi/` (pure, describes), `host/` (actualises over Pulumi),
  `exec/` (executables). Sorted by purity (osgi↔host) + nature (exec). "Two spaces" names the PURITY
  seam; `exec/` is the orthogonal NATURE axis, NOT a third purity space — so the atlas "two spaces"
  frame stays correct.
- **world** — a RUNTIME classloader region: OSGi world (bundle classloaders, NO gRPC ever) vs HOST world
  (one flat classloader, holds gRPC). Same osgi/host line as the spaces, seen at runtime not at layout.
- **universe** — RESERVED for the resolver sense only: `UnitResolver`'s candidate set
  (`UnitResolver(List<UnitResource> universe)`), `ManifestsUniverse`, "the manifests universe". NOT a
  synonym for "system". Renaming the resolver field was considered and rejected (code churn for no gain);
  instead "system" fills the whole-system slot and "universe" stays the resolver's.

## The role triad (the verbs)

- `osgi/` **describes** (pure models, the ports + impls).
- `host/` **actualises** the description over the Pulumi engine.
- `exec/` **materialises** the system: `seed-master` embeds BOTH worlds — the OSGi bundles (describe) +
  the host adapters (actualise) — into ONE self-contained launchable jar that boots Felix inside
  `Pulumi.run` (R4). This is the role the terminology lacked a verb for.

## The three layout axes (target-layout spec §2, unchanged — this glossary just names their product)

- *purity (WHO):* osgi describes / host actualises.
- *direction (WHICH WAY):* north (Provide/offer) vs south (Require/depend).
- *nature (WHAT KIND):* library vs executable; `exec/` carves the executables out, orthogonal to purity.

## Where it is written

The atlas (`docs/architecture/integration-atlas.adoc`) carries this as a NOTE in the "two spaces"
section. When writing specs/docs/memory, use these words with these meanings — precision of naming is a
project principle ([[every-module-has-a-description]] sibling discipline). See
[[osgi-runtime-r4-boot-seam-state]] (the self-contained jar that materialises the system),
[[api-extraction-tri-carto-state]] (bridge-api lives in the host world), the atlas "two spaces" + runtime
view.
