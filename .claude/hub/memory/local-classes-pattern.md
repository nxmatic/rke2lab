---
name: local-classes-pattern
description: Local classes vs inner classes pattern for scope reduction
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 7015d8cd-a561-41e1-a191-158fcfc16456
---

Prefer **local classes** (defined within methods) over inner classes when the class is only used in a single method and is reasonably simple.

**Rule**: If a class has only ONE instantiation site and that site is in ONE method, move it into that method as a local class.

## Why

1. **Reduced namespace pollution**: Large classes like `IncusResourceBootstrap` (171 private methods) become harder to navigate with many inner classes
2. **Explicit scope**: Impossible to accidentally use the class elsewhere
3. **Colocated logic**: Implementation is right where it's used
4. **Refactoring safety**: Delete the method → class goes with it

## When to use local classes

- Class used in only one method
- Class is simple/short (< ~50 lines)
- Encapsulates method-specific logic

## When to keep as inner class

- Class shared across multiple methods
- Class is complex/long (50+ lines) — would hurt method readability
- Part of fluent pipeline pattern (e.g., `SynthesisPipeline` with 6 stages, 200+ lines)

## Examples refactored (2026-06-03)

1. **ImageStateSynthesizer** (IncusResourceBootstrap) — was inner static class, now local in `synthesizeImageStateConfigMapYaml()`
   - Single use site
   - ~130 lines but most was extracted inline into method
   - Result: method is now self-contained with local `record ImageStateData`

2. **NetworkEnsurer** (IncusResourceBootstrap) — was inner non-static class, now local `NetworkSetup` in `ensureNetwork()`
   - Single use site
   - 7 helper methods encapsulated
   - Needs access to outer instance (non-static)

3. **SynthesisPipeline** (DefaultManifestSynthesisService) — KEPT as inner class
   - Single use site but 200+ lines with 6 stages
   - Too complex to inline in method

## Pattern added to CLAUDE.md

Section "Local classes vs inner classes" added after "Lazy instantiation pattern" with full guidelines and examples.

**How to apply**: [[rke2lab:refactor-pipeline-candidates]]
