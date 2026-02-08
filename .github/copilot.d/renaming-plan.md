# Renaming & Refactor Plan

## Goals
- Consolidate readiness checks into post-start execution where appropriate.
- Standardize naming:
  - `*-manifests-install.service` → `*-manifests.service`
  - `*-ready-check.service` → `*-ready.service`
  - Script names: `*-ready-check.sh` → `*-ready.sh`
- Reduce target chaining where readiness checks can be run as post-start steps.

## Proposed Phases

### Phase 1: Inventory & Mapping
- Enumerate all custom systemd units and scripts.
- Map current names to new names.
- Identify dependencies and ordering constraints.

### Phase 2: Naming Refactor
- Rename unit files to the new naming scheme.
- Rename scripts to the new naming scheme.
- Update references across:
  - unit dependencies
  - activation scripts
  - docs and diagrams

### Phase 3: Readiness Integration
- For each layer, move readiness checks into a post-start action of the corresponding manifests service (or another suitable unit).
- Remove now-redundant `*-ready.service` targets where post-start provides equivalent gating.
- Keep only necessary targets for cross-layer ordering.

### Phase 4: Validation
- Ensure systemd load and dependency graph is coherent.
- Confirm readiness checks still run and surface failures.
- Verify that renamed units are enabled and invoked by activation scripts.

## Open Decisions
- Which layers still require explicit targets after post-start refactor.
- Whether any readiness checks must remain standalone due to timing constraints.
- Whether to keep a single “global ready” target for observability.
