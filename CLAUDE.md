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
