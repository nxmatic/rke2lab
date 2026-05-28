# Project conventions

## Build & module layout

- Maven multi-module project. CLI selectors use the unprefixed module name: `./mvnw -pl :seed-bootstrap` (not `:rke2lab-seed-bootstrap`).
- Group ids are nested under `io.nxmatic.rke2lab` (and `io.nxmatic.rke2lab.sdks` for the `sdks/` tree). Artifact ids match the directory name.
- `<name>` in each pom is the relative directory path from the repo root.
- Toolchain is JDK 25 via flox. Always run Maven through `flox activate -- ./mvnw …`. Builds and provisioning are run by the user — propose fixes, don't run mvnw/pulumi/kubectl yourself.

## Fluent pipeline grammar

Multi-stage workflows in seed-bootstrap follow a documented fluent grammar. The full design lives at [docs/fluent-pipeline-grammar.adoc](docs/fluent-pipeline-grammar.adoc). Summary:

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
