# Project conventions

## Build & module layout

- Maven multi-module project. CLI selectors use the unprefixed module name: `./mvnw -pl :seed-master` (not `:rke2lab-seed-master`).
- Group ids are nested under `io.nxmatic.rke2lab` (and `io.nxmatic.rke2lab.sdks` for the `sdks/` tree). Artifact ids match the directory name.
- `<name>` in each pom is the relative directory path from the repo root.
- Toolchain is JDK 25 via flox. Always run Maven through `flox activate -- ./mvnw …`. Builds and provisioning are run by the user — propose fixes, don't run mvnw/pulumi/kubectl yourself.

## Fluent pipeline grammar

Multi-stage workflows in seed-master follow a documented fluent grammar. The full design lives at [docs/fluent-pipeline-grammar.adoc](docs/fluent-pipeline-grammar.adoc). Summary:

- **Topic blocks** are entered with `.during("label", lambda)`. The lambda receives a topic-specific builder so only that topic's verbs are callable inside.
- **Conjunctions** between topics are explicit: `.then()` is mandatory between `during(...)` calls. It exists for readability, not data flow.
- **Labels are real**: used for entry/exit logging and to wrap exceptions as `PipelineStageFailure(label, cause)`.
- **Order is type-state-enforced**: each stage returns a distinct class, so reordering, skipping, or premature termination won't compile.
- **Errors are per-topic** and fail-fast by default.

When you encounter a builder with three or more boolean parameters or a sequence of method calls that need comments to explain stage boundaries, that's a candidate for the grammar. When in doubt, read the doc and look at the exemplar in `controlplane/pipeline/`.

## Code style

- Comments are exception, not the rule. Only document the *why* behind non-obvious choices. Do not narrate what the code does or reference task numbers / past callers.
- Avoid backwards-compatibility shims, dead-code comments, or speculative abstractions. Three similar lines beat a premature helper.
- Prefer editing existing files to creating new ones. Don't create design docs unless asked.
- No deprecation warnings. This is a single-developer project — when refactoring, delete the old API entirely and update all call sites in the same change.

## Design principles

- **Immutability by default**: When a class is mainly immutable and not designed for subclassing, convert it to a record. If mutation is needed later, generate a new instance via `with*` methods or a builder.
- **Builder enforcement**: If a class offers a builder and has multi-parameter constructors, make the constructor private to enforce builder usage and ease review. The builder pattern signals complex construction; direct construction bypasses that contract.
- **Functional APIs**: Design for composition and pipelines. Prefer fluent chains, function parameters (lambdas/method refs), and immutable transformations over stateful accumulators.
- **Multi-parameter methods**: When you encounter methods with 3+ parameters (especially booleans), note them as candidates for pipeline-based implementation. Consider whether the fluent grammar or a builder would improve readability and type safety.
- **Prefer instances over helpers**: Pass object instances through the call graph rather than creating static helper methods. This makes dependencies explicit, enables testing/mocking, and keeps state encapsulated. See "Instance-passing discipline" below.
- **Single source of truth for identifiers**: Use typed accessor methods from canonical registries (like `ManifestDomainCatalog`) instead of hardcoded string literals. This prevents identifier mismatches like the `clusterApi` bug where `"clusterApi"` string didn't match catalog's `"cluster-api"` ID. See "ManifestDomainCatalog discipline" below.

### Instance-passing discipline

**Always prefer passing instances over creating static helpers.**

When you need functionality from another component:

✅ **DO**: Pass the instance through the call path

```java
// Caller creates and passes the instance
SystemdTarget systemdTarget = new SystemdTarget();
systemdTarget.materialize(paths);
FloxRuntimeAssets floxAssets = systemdTarget.getFloxRuntimeAssets();
List<DiscoveredEnvironment> envs = floxAssets.getDiscoveredEnvironments();
```

❌ **DON'T**: Create static helper methods

```java
// Anti-pattern: static helper hides dependencies
List<DiscoveredEnvironment> envs = FloxEnvironmentHelper.discover();
```

**Why this matters:**

1. **Explicit dependencies**: The call graph shows what data flows where. Static helpers hide this.
2. **Testability**: Instances can be mocked or stubbed. Static methods can't (without PowerMock hacks).
3. **State encapsulation**: Instances own their state. Static helpers either have no state (re-computing expensively) or hidden global state (coupling).
4. **Refactoring safety**: IDEs track instance passing. Static calls are harder to refactor.
5. **Context availability**: Instances can hold context (config, logger, metrics). Static methods can't without passing everything as parameters.

**Common scenarios:**

- **Discovered data** (like flox environments): The discovery is expensive and should happen once. Store in an instance field, expose via getter.
- **Configuration**: Pass `BootstrapConfig` or `ControlplanePolicy` instances, don't make static accessors.
- **Resource handles** (git repo, file trees): Wrap in an instance that manages lifecycle. Don't make static "open/close" helpers.

**When static methods ARE appropriate:**

- Pure utility functions with no state: `Math.max()`, `String.format()`, `Collections.unmodifiableList()`
- Factory methods: `ClassName.builder()`, `ClassName.of(...)`, `ClassName.parse(...)`
- Type-safe enum conversions: `SlotType.fromYamlValue(String)`

**Pattern evolution example:**

Starting point (static helper anti-pattern):

```java
// FloxEnvironmentHelper.java
public class FloxEnvironmentHelper {
  public static List<DiscoveredEnvironment> discover() {
    // Re-scans classpath every call - expensive!
  }
}
```

Refactored to instance (correct):

```java
// FloxRuntimeAssets.java
public class FloxRuntimeAssets {
  private final List<DiscoveredEnvironment> discovered;  // Computed once
  
  public List<DiscoveredEnvironment> getDiscoveredEnvironments() {
    return discovered;
  }
}

// Caller
FloxRuntimeAssets assets = FloxRuntimeAssets.builder().build();
List<DiscoveredEnvironment> envs = assets.getDiscoveredEnvironments();
```

**Enforcement:**

When reviewing code or implementing features:

- If you find yourself writing `public static X doSomething()`, ask: "Should this be an instance method?"
- If you're about to create a `XyzHelper` class with static methods, ask: "Which existing class should own this behavior?"
- If you see `SomeHelper.staticMethod()` calls, refactor to pass the instance.

### ManifestDomainCatalog discipline

**Never use hardcoded domain ID strings. Always use the ManifestDomainCatalog.**

On May 31, 2026, we fixed a critical bug where `clusterApi` policy showed `false` in MANIFEST.yaml despite being configured as `true`. Root cause: hardcoded string `"clusterApi"` didn't match the catalog's actual ID `"cluster-api"` (kebab-case). The mismatch caused `policy.isEnabled("clusterApi")` to silently return `false` (default) instead of the configured `true`.

**The fix**: Import `ManifestDomainCatalog` and use typed methods.

✅ **DO**: Use catalog accessor methods

```java
private static final ManifestDomainCatalog CATALOG =
    ManifestDomainCatalog.builder().addDefaultDomains().build();

// Domain registration
return new LayerDomain(
    CATALOG.gitops(),                    // ✅ Type-safe
    List.of(CATALOG.replication()),      // ✅ No typos possible
    units);

// Policy queries
if (policy.isEnabled(CATALOG.porch())) { // ✅ Guaranteed match
  units.add(new PorchResourcesManifestUnit());
}
```

❌ **DON'T**: Use magic strings

```java
// Anti-patterns that caused the clusterApi bug:
return new LayerDomain("gitops", List.of("replication"), units);  // ❌ Duplicates catalog
if (policy.isEnabled("porch")) { ... }                             // ❌ Could typo to "Porch"
manifestDomain.put("clusterApi", policy.isEnabled("clusterApi")); // ❌ Mismatch: "clusterApi" ≠ "cluster-api"
```

**Why multi-word domains are dangerous**:
- `catalog.clusterApi()` returns `"cluster-api"` (kebab-case)
- `catalog.highAvailability()` returns `"high-availability"` (kebab-case)
- Hardcoding `"clusterApi"` creates a mismatch → silent failure

**Enforcement**:
- Grep in code review: `! grep -r 'isEnabled("' manifests/src/ seed-master/src/`
- When adding a new domain, add the accessor method to `ManifestDomainCatalog` first
- See `docs/manifest-domain-catalog-pattern.adoc` for full pattern documentation

### Single-source-of-truth pattern for identifiers

**The pattern**: ManifestDomainCatalog, BootstrapPaths.HostPathCatalog, and SystemdUnitCatalog all follow the same principle - **define identifiers once, reference everywhere**.

**Why**: Identifier mismatches cause silent failures. Three bugs fixed on May 31, 2026:

1. `"clusterApi"` string vs `"cluster-api"` catalog ID → policy showed false
2. `"manifests.d"` directory vs `"/srv/host/rke2-manifests.d"` constant → units never started (condition failed)
3. `"rke2lab-clusterapi-manifests.service"` dependency vs `"rke2lab-cluster-api-manifests.service"` filename → systemd "Unit not found"

**When to create a catalog**:

- Multiple files reference the same identifier (domain ID, path, unit name, etc.)
- Identifier format is non-obvious (kebab-case, camelCase, with/without prefix)
- Mismatch would cause silent failure or runtime error

**Existing catalogs**:

- `ManifestDomainCatalog` - manifest domain IDs
- `BootstrapPaths.HostPathCatalog` - container mount paths  
- `SystemdUnitCatalog` - systemd unit filenames

**Template**:

```java
public final class XyzCatalog {
  public static final String SOME_ID = "actual-value";
  // ... more constants
  
  private XyzCatalog() {} // Utility class
}
```

For systemd units specifically: unit files can't use Java code, so the catalog serves as the **authoritative registry during code review**. When writing `After=some-unit.service`, verify the name exists in `SystemdUnitCatalog`.

## Documentation standards

Documentation is critical for context recovery. This is a complex, multi-concern project where the developer needs to frequently context-switch between different domains (Incus, Kubernetes, Cluster API, GitOps, systemd, networking). High-quality documentation prevents re-learning and accelerates future work.

### When to document

Document architectural decisions, patterns, and workflows proactively, especially when:

- Implementing a new subsystem or cross-cutting concern
- Making non-obvious design decisions (e.g., "why not constructor parameters?")
- Establishing patterns that will be reused (e.g., manifest unit access patterns)
- Completing a phase or deliverable that hands off to future work

**Don't wait until asked.** If the implementation revealed complexity or required clarification during development, that's a signal to document.

### Documentation quality standard

Follow the pattern established in `docs/bootstrap-identity-provider.adoc` (commit c324fa05):

**Required elements**:

1. **Overview** - What is this? Why does it exist? What problem does it solve?

2. **C4 Architecture Diagrams** (using Mermaid)
   - Context diagram showing separation of concerns
   - Component or sequence diagram showing data flow
   - Color-coded legend explaining component types

3. **Usage Patterns**
   - Show the correct pattern with code examples
   - Call out anti-patterns (❌ Don't do this / ✅ Do this instead)
   - Explain WHY the correct pattern is better

4. **Setup/Configuration** - If applicable, step-by-step bootstrap instructions

5. **Troubleshooting** - Common errors with causes and fixes

6. **Related Documentation** - Bidirectional cross-references to other docs

### Format and style

- **Use AsciiDoc** (`.adoc`) for consistency with existing docs
- **Mermaid diagrams** for architecture visualization (not PlantUML)
- **Code examples** should be runnable and match actual implementation
- **Organize by concern**, not chronologically
- **Clear section headers** - reader should find what they need quickly

### Cross-referencing discipline

When you create or update documentation:

1. **Add forward links** FROM your doc TO related docs
2. **Add backward links** FROM related docs TO your doc  
3. **Update `docs/README.adoc`** with your doc in the appropriate section
4. **Add navigation hints** if your doc is part of a learning flow

Example cross-reference block (end of document):

```asciidoc
== Related Documentation

* link:other-doc.adoc[Other Doc] - Brief description of relationship
* link:another-doc.adoc[Another Doc] - Why reader might go there next
```

### Documentation checklist

Before considering architecture work complete:

- [ ] Core concepts explained with "why" not just "what"
- [ ] Anti-patterns called out (prevents future duplication of mistakes)
- [ ] C4 diagram showing concerns and data flow
- [ ] Code examples demonstrate actual usage
- [ ] Bidirectional cross-references to related docs
- [ ] `docs/README.adoc` updated with new document
- [ ] Troubleshooting section with common errors

### Why this matters

**Reference example**: The bootstrap identity provider documentation prevented a near-duplication where constructor parameters were about to be added to manifest units. The documentation made it clear:

- ❌ Constructor parameters for runtime config = wrong (statically instantiated units can't receive them)
- ✅ Context access via `bootstrapIdentity()` = correct (ThreadLocal injection)

Without documentation, this mistake would have been discovered later during actual synthesis, requiring rework. The doc caught it at design time.

**Impact**: High-quality docs let you context-switch away from a subsystem and return weeks later without re-learning. They prevent architectural mistakes during future work. They make code review more effective because reviewers can understand the "why" behind decisions.
