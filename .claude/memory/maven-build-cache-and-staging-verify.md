---
name: maven-build-cache-and-staging-verify
description: How to invoke the build correctly in rke2lab — skip (not disable) the build cache, use -Pall-worlds, and verify staging-extension changes with a full package (never generate-resources).
metadata:
  type: feedback
---

The canonical build invocation for rke2lab tests (user-confirmed):

```
./mvnw -Dtest=<Test> package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false 2>&1 | tee /tmp/maven.log
```

**Why:** the repo uses the `maven-build-cache-extension`. To get a fresh run, **SKIP** the cache with
`-Dmaven.build.cache.skipCache=true` — do NOT disable it with `-Dmaven.build.cache.enabled=false`.
- `skipCache=true` bypasses the restore-from-cache lookup for this run while keeping the extension
  ACTIVE (still computes checksums and saves). This is what you want for a clean verify.
- `enabled=false` turns the whole mechanism off; tried it once and it caused sibling jars to not
  materialize for dependency resolution (the staging extension reads dep jars from disk). Wrong tool.

**`-Pall-worlds`** is part of the canonical command — use it for test runs.

**Verifying `maven-embed-staging-ext` (the OSGi bundle-staging core extension) changes:** the
extension is a `MojoExecutionListener` that fills the empty `<artifactItems/>` of the
`stage-embedded-bundles` dependency:copy execution by reading each runtime dependency's JAR **from
disk**. Therefore:
- It needs sibling modules **packaged** (jars on disk). A `./mvnw -pl :seed-master -am generate-resources`
  run does NOT package siblings → `artifactItems` stays empty → `Either artifact or artifactItems is
  required`. That is a verification-harness artifact, NOT a real bug.
- Verify with a **full reactor `package`** from the repo root (every upstream module packaged before
  the exec module), scoping the test with `-Dtest=...`.

Related: [[cdk8s-carrier-flat-jar-pattern]] (the staging closure this extension computes).
