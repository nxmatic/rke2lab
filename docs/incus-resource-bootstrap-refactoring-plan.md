# IncusResourceBootstrap Refactoring Plan

## Current State Analysis

### What's Already Good ✅

1. **Outer Pipeline Structure** - The `apply()` method follows fluent pipeline grammar:
   ```java
   new ApplyStart(pipeline)
       .onFailure((topic, cause) -> SeedLog.error("incus", topic + ": " + cause.getMessage()))
       .during("path resolution", paths -> paths.resolve())
       .then()
       .during("host state", host -> host.prepareAll())
       .then()
       .during("provider resources", provider -> provider.ensureAll())
       .then()
       .during("instance", instance -> instance.create())
       .toResult();
   ```

2. **Type-State Enforcement** - Proper use of type-state classes:
   - `ApplyStart` → `PathDone` → `HostDone` → `ProviderDone` → `InstanceDone`
   - Compiler enforces stage ordering
   - Cannot skip `.then()` or reorder topics

3. **Recent Refactorings** - Already improved:
   - `prepareHostState()` broken into helper methods (materializeToStaging, registerProvisioningSlices, etc.)
   - `buildManifestSynthSummary()` extracted into focused methods
   - `synthesizeAndExplodeManifests()` split into single-purpose operations

### What Needs Improvement ❌

1. **Topic Stages Are Too Thin**
   ```java
   // Current: just delegates to ApplyPipeline
   private static final class HostStage {
     HostStage prepareAll() {
       pipeline.prepareHostState();  // ← 80+ lines hidden here
       return this;
     }
   }
   ```

   **Problem**: All logic lives in `ApplyPipeline`, stages are just wrappers.

2. **ApplyPipeline is a Mutable God Object**
   - 10+ mutable fields
   - All business logic lives here
   - Violates single responsibility principle
   - Hard to test individual stages

3. **Complex Methods Still Exist**
   - `existingProjectId()` - 207 lines
   - `upsertGithubCredentialsPreservingComments()` - 90 lines
   - `upsertFloxCredentialsPreservingComments()` - 68 lines
   - `clearTargetRoot()` - 70 lines

4. **File Size** - 3184 lines in one file
   - Difficult to navigate
   - Hard to review
   - Violates separation of concerns

## Target Architecture

### Phase 1: Extract State (High Priority)

**Current**:
```java
private final class ApplyPipeline {
  private BootstrapPaths localPaths;       // ← mutable
  private IncusProviderContext providerContext;
  private DeploymentMetadata deploymentMetadata;
  // ... 10+ more fields

  private ApplyPipeline prepareHostState() {
    // logic here
    return this;
  }
}
```

**Target**:
```java
static final class ApplyState {
  BootstrapPaths localPaths;
  BootstrapPaths nixosPaths;
  IncusProviderContext providerContext;
  // ... immutable or carefully managed state

  // No business logic, just data
}

private static final class HostStage {
  private final ApplyState state;

  HostStage materializeAssets() {
    // logic here, accesses state
    return this;
  }

  HostStage registerSlices() {
    // logic here
    return this;
  }

  HostStage syncToFinal() {
    // logic here
    return this;
  }
}
```

**Benefits**:
- Clear separation: State vs Behavior
- Topic stages are self-contained
- Easier to test each stage independently
- Follows fluent pipeline grammar fully

### Phase 2: Self-Contained Topic Stages (High Priority)

Each topic stage should expose fluent methods for its sub-operations:

```java
// Instead of: .during("host state", host -> host.prepareAll())
// Target:
.during("host state", host -> host
    .materializeAssets()
    .registerSlices()
    .syncToFinal())
```

**Stages to Refactor**:
1. **PathStage**: `.resolve()` → simple enough, keep as-is
2. **HostStage**: `.prepareAll()` → `.materializeAssets().registerSlices().syncToFinal()`
3. **ProviderStage**: `.ensureAll()` → `.ensureProject().ensureProfile().ensureImage()`
4. **InstanceStage**: `.create()` → `.createAndConfigure()` (or break down further)

### Phase 3: Break Down Complex Methods (Medium Priority)

Target: No method over 50 lines.

**High-Impact Methods** (>100 lines):
1. `existingProjectId()` - 207 lines
   - Extract: `findProjectByName()`, `findProjectByInvoke()`, `handleInvokeErrors()`

2. `upsertGithubCredentialsPreservingComments()` - 90 lines
   - Extract: `parseEnvFile()`, `updateCredentialLine()`, `preserveComments()`

3. `upsertFloxCredentialsPreservingComments()` - 68 lines
   - Similar to GitHub, extract parsing and updating logic

4. `clearTargetRoot()` - 70 lines
   - Extract: `identifyPhases()`, `clearPhaseFiles()`, `validateRetention()`

**Pattern to Apply**:
```java
// Before: 100-line method doing everything
private void complexOperation() {
  // 20 lines of setup
  // 30 lines of core logic
  // 20 lines of validation
  // 30 lines of cleanup
}

// After: orchestration + focused helpers
private void complexOperation() {
  setup();
  performCoreLogic();
  validate();
  cleanup();
}

private void setup() { /* focused 15 lines */ }
private void performCoreLogic() { /* focused 20 lines */ }
private void validate() { /* focused 10 lines */ }
private void cleanup() { /* focused 15 lines */ }
```

### Phase 4: Extract Large Nested Classes (Lower Priority)

Some nested classes are effectively separate services:

1. **RuntimeEnvControlplaneOverlayWriter** - 91 lines
   - Extract to: `io.nxmatic.rk2lab.controlplane.incus.env.RuntimeEnvWriter`
   - Benefits: reusable, testable, clearer responsibility

2. **DaemonsetLogPolicy** - 53 lines
   - Extract to: `io.nxmatic.rk2lab.controlplane.incus.daemonset.LogPolicy`

3. **ProvisioningResourceInventory** - 48 lines
   - Extract to: `io.nxmatic.rk2lab.controlplane.incus.inventory.ResourceInventory`

4. **SystemdProvisioningInventory** - 34 lines
   - Extract to: `io.nxmatic.rk2lab.controlplane.incus.inventory.SystemdInventory`

**Decision Criteria**:
- Is the class >50 lines?
- Does it have a clear, single responsibility?
- Could it be reused elsewhere?
- Would extraction improve testability?

If yes to ≥3, extract to separate file.

### Phase 5: File Size Reduction (Optional)

If the file remains >2000 lines after above refactorings:

**Option A**: Split by topic
- `IncusResourceBootstrap.java` - main orchestration
- `IncusPathResolution.java` - path-related logic
- `IncusHostPreparation.java` - host state logic
- `IncusProviderSetup.java` - provider resource logic
- `IncusInstanceCreation.java` - instance creation logic

**Option B**: Split by concern
- `IncusResourceBootstrap.java` - pipeline orchestration only
- `IncusBootstrapStages.java` - all topic stage implementations
- `IncusBootstrapHelpers.java` - shared utility methods

**Recommendation**: Start with Phase 1-3, reassess after.

## Implementation Strategy

### Week 1: State Extraction
- [ ] Create `ApplyState` class with all fields from `ApplyPipeline`
- [ ] Pass `state` to all topic stage constructors
- [ ] Remove business logic from `ApplyPipeline` methods
- [ ] Delete `ApplyPipeline` class

### Week 2: Self-Contained Stages
- [ ] Add fluent methods to `HostStage`
- [ ] Add fluent methods to `ProviderStage`
- [ ] Update `apply()` call site to use new methods
- [ ] Verify type-state enforcement still works

### Week 3: Method Complexity Reduction
- [ ] Refactor `existingProjectId()` (highest impact)
- [ ] Refactor credential upsert methods (2 methods)
- [ ] Refactor `clearTargetRoot()`
- [ ] Review all methods >50 lines, refactor as needed

### Week 4: (Optional) Extract Services
- [ ] Extract `RuntimeEnvControlplaneOverlayWriter`
- [ ] Extract `DaemonsetLogPolicy`
- [ ] Extract inventory classes
- [ ] Update import statements and references

## Success Metrics

- [ ] All topic stages follow fluent pipeline grammar
- [ ] No method exceeds 50 lines
- [ ] No method has >3 responsibilities
- [ ] `ApplyState` class exists, `ApplyPipeline` doesn't
- [ ] File compiles without errors
- [ ] All tests pass (when test suite exists)
- [ ] Code review takes <30 minutes (vs current ~2 hours)

## References

- [fluent-pipeline-grammar.adoc](./fluent-pipeline-grammar.adoc) - Full grammar spec
- [ApplicationPipeline.java](../seed-master/src/main/java/io/nxmatic/rk2lab/controlplane/pipeline/ApplicationPipeline.java) - Reference implementation

## Notes

This is a large refactoring that should be done incrementally. Each phase can be a separate PR, making reviews manageable and allowing rollback if issues arise.

The key insight: **The outer pipeline structure is already correct**. We just need to:
1. Move logic from `ApplyPipeline` into topic stages
2. Extract complex methods into focused helpers
3. Separate data (State) from behavior (Stages)

This follows the established fluent pipeline grammar and makes the codebase more maintainable.
