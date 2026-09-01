package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The in-cluster render pipeline — the upstream half of the GitOps loop rendered as Tekton
 * manifests (see {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc}). Two Tasks
 * wired by one Pipeline, plus the persistent Maven-cache PVC:
 *
 * <ul>
 *   <li>{@code git-fetch} — clones the source repo at the pushed revision into the shared {@code
 *       source} workspace, authenticating with PaC's {@code basic-auth} workspace (the {@code
 *       git_auth_secret} App token PaC injects into the PipelineRun).
 *   <li>{@code render-publish} — {@code flox-annotated} (the NRI plugin injects a per-task FloxEnv
 *       under the {@code cicd} folder — JDK 25 + maven for this render task — into the {@code
 *       step-render} container), builds with the reactor discipline ({@code -pl :manifests-cli -am
 *       clean verify}, siblings from {@code target/}, never installed), then runs the {@code
 *       publish} verb — render into the plot + ff-push {@code manifests/<cluster>}.
 * </ul>
 *
 * <p><b>Flox injection lives on the PipelineRun stub, not here.</b> Pod annotations are set by the
 * PipelineRun (the {@code .tekton/} source stub), not by a Pipeline or Task. The stub carries
 * {@code flox.dev/environment.step-render=cicd/<task-env>} (+ home/uid/gid) — the CI flox envs are
 * specialised PER TASK under the {@code cicd} folder ({@link
 * io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxEnvFolder#CICD}), not one coarse toolchain.
 * It propagates to every task pod, and only the {@code render-publish} pod owns a {@code
 * step-render} container, so the NRI plugin injects there and ignores it on the {@code git-fetch}
 * pod (no bare-key fallback — each container opts in BY NAME).
 *
 * <p><b>Workspaces:</b> the Pipeline DECLARES {@code source} (per-run, bound by the stub to a
 * {@code volumeClaimTemplate}), {@code maven-cache} (the persistent RWO PVC rendered here), and
 * {@code basic-auth} (PaC's {@code git_auth_secret}); the PipelineRun stub BINDS them. The build is
 * serialised (concurrency 1, set on the PaC {@code Repository}/stub) — a Maven local repo + build-
 * cache are not multi-writer safe.
 *
 * <p>The push token is wired: the {@code render-publish} step extracts PaC's App token from the
 * mounted {@code git_auth} secret into {@code RKE2LAB_PUSH_TOKEN}, which the in-cluster {@code
 * publish} reveals for the ff-push (container-aware {@code
 * ManifestSynthesisScenario.revealGithubToken} — cellar OPERATOR, env IN_CLUSTER). The one forward
 * reference left is the render task's {@code cicd/<task-env>} FloxEnv (JDK 25 / maven), which the
 * flox-catalogue branch lands; the objects render structurally now so the resources materialise and
 * reconcile.
 */
public final class RenderPipelineManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/render-pipeline";

  private static final String NAMESPACE = "tekton-pipelines";

  private static final String PIPELINE_NAME = "render-manifests";

  private static final String MAVEN_CACHE_PVC = "manifests-maven-cache";

  /** Container name the flox NRI plugin keys on: {@code flox.dev/environment.step-render}. */
  private static final String RENDER_STEP = "render";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "render-pipeline");

  public RenderPipelineManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createMavenCachePvc(scope);
    createGitFetchTask(scope);
    createRenderPublishTask(scope);
    createPipeline(scope);
  }

  private void createMavenCachePvc(final Construct scope) {
    final ApiObject pvc =
        new ApiObject(
            scope,
            "persistentvolumeclaim-manifests-maven-cache",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MAVEN_CACHE_PVC)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|PersistentVolumeClaim|" + NAMESPACE + "|" + MAVEN_CACHE_PVC))
                        .build())
                .build());
    // RWO: the ~/.m2 local repo + maven-build-cache are single-writer; the pipeline runs
    // concurrency 1 so no two renders race the cache (and a single-node cluster co-locates anyway).
    // openebs-zfs-shared (shared=yes bind-mount): Tekton's affinity assistant may co-mount this
    // volume alongside the task pod on the same node, which the default exclusive SC rejects.
    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                List.of("ReadWriteOnce"),
                "storageClassName",
                "openebs-zfs-shared",
                "resources",
                Map.of("requests", Map.of("storage", "10Gi")))));
  }

  private void createGitFetchTask(final Construct scope) {
    final ApiObject task =
        new ApiObject(
            scope,
            "task-git-fetch",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Task")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("git-fetch")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Task|" + NAMESPACE + "|git-fetch"))
                        .build())
                .build());
    task.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "repo-url", "type", "string"),
                  Map.of("name", "revision", "type", "string")
                },
                "workspaces",
                new Object[] {
                  Map.of("name", "output"), Map.of("name", "basic-auth", "optional", true)
                },
                "steps",
                new Object[] {
                  Map.of(
                      "name",
                      "clone",
                      "image",
                      "alpine/git:2.45.2",
                      "script",
                      String.join(
                          "\n",
                          "#!/bin/sh",
                          "set -eu",
                          // PaC's git_auth_secret ships .gitconfig + .git-credentials; adopt them
                          // so
                          // the clone authenticates as the App without embedding a token in the
                          // URL.
                          "if [ -f \"$(workspaces.basic-auth.path)/.git-credentials\" ]; then",
                          "  cp \"$(workspaces.basic-auth.path)/.git-credentials\" \"$HOME/.git-credentials\"",
                          "  cp \"$(workspaces.basic-auth.path)/.gitconfig\" \"$HOME/.gitconfig\"",
                          "fi",
                          "cd \"$(workspaces.output.path)\"",
                          "git init -q .",
                          "git remote add origin \"$(params.repo-url)\"",
                          "git fetch -q --depth 1 origin \"$(params.revision)\"",
                          "git checkout -q FETCH_HEAD"))
                })));
  }

  private void createRenderPublishTask(final Construct scope) {
    final ApiObject task =
        new ApiObject(
            scope,
            "task-render-publish",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Task")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("render-publish")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Task|" + NAMESPACE + "|render-publish"))
                        .build())
                .build());
    task.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "cluster", "type", "string"),
                  Map.of("name", "node", "type", "string")
                },
                "workspaces",
                new Object[] {
                  Map.of("name", "source"),
                  Map.of("name", "maven-cache"),
                  Map.of("name", "basic-auth", "optional", true)
                },
                "steps",
                new Object[] {
                  Map.of(
                      // Container name = step-render; the PipelineRun stub flox-annotates it so the
                      // NRI plugin injects the cicd/toolchain FloxEnv (JDK 25 + maven) here.
                      "name",
                      RENDER_STEP,
                      // A glibc base; the toolchain (java/maven) arrives via flox injection, not
                      // the
                      // image. No JDK baked in — dogfooding the flox NRI runtime.
                      "image",
                      "debian:stable-slim",
                      "workingDir",
                      "$(workspaces.source.path)",
                      "script",
                      String.join(
                          "\n",
                          "#!/usr/bin/env bash",
                          "set -euo pipefail",
                          // PaC minted an App token into the mounted git_auth secret; extract it
                          // into RKE2LAB_PUSH_TOKEN so the in-cluster publish reveals it for the
                          // ff-push (the scion reads it in-container —
                          // ManifestSynthesisScenario.revealGithubToken). Backticks, not $(...), so
                          // Tekton doesn't mistake the shell substitution for one of its own vars.
                          "GIT_AUTH_DIR=\"$(workspaces.basic-auth.path)\"",
                          "if [ -f \"$GIT_AUTH_DIR/.git-credentials\" ]; then",
                          "  export RKE2LAB_PUSH_TOKEN=`sed -E 's#https://[^:]+:([^@]+)@.*#\\1#'"
                              + " \"$GIT_AUTH_DIR/.git-credentials\" | head -n1`",
                          "fi",
                          // The flox NRI plugin MOUNTED the cicd/maven env at /root/.flox but does
                          // NOT auto-activate — the command must, exactly like the kdns container's
                          // `flox activate --dir /root -- kdns`. Run build+publish inside the
                          // activated env so JDK 25 + maven are on PATH (flox itself is on PATH via
                          // the plugin's injection). -am builds siblings from target/; verify runs
                          // the tests + staging gates before any push; never `install`.
                          "flox activate --dir /root -- bash -euo pipefail -c '",
                          "  ./mvnw -Dmaven.repo.local=\"$(workspaces.maven-cache.path)/repository\""
                              + " -pl :manifests-cli -am clean verify",
                          "  java \\",
                          "    -Drke2lab.manifests.outdir=\"$(workspaces.source.path)/render\" \\",
                          "    -Drke2lab.manifests.cluster=\"$(params.cluster)\" \\",
                          "    -Drke2lab.manifests.node=\"$(params.node)\" \\",
                          "    -jar exec/manifests-cli/target/manifests-cli-*-exec.jar publish",
                          "'"))
                })));
  }

  private void createPipeline(final Construct scope) {
    final ApiObject pipeline =
        new ApiObject(
            scope,
            "pipeline-render-manifests",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Pipeline")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PIPELINE_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Pipeline|" + NAMESPACE + "|" + PIPELINE_NAME))
                        .build())
                .build());
    pipeline.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "repo-url", "type", "string"),
                  Map.of("name", "revision", "type", "string"),
                  Map.of("name", "cluster", "type", "string"),
                  Map.of("name", "node", "type", "string")
                },
                "workspaces",
                new Object[] {
                  Map.of("name", "source"),
                  Map.of("name", "maven-cache"),
                  Map.of("name", "basic-auth", "optional", true)
                },
                "tasks",
                new Object[] {
                  Map.of(
                      "name",
                      "fetch",
                      "taskRef",
                      Map.of("name", "git-fetch"),
                      "params",
                      new Object[] {
                        Map.of("name", "repo-url", "value", "$(params.repo-url)"),
                        Map.of("name", "revision", "value", "$(params.revision)")
                      },
                      "workspaces",
                      new Object[] {
                        Map.of("name", "output", "workspace", "source"),
                        Map.of("name", "basic-auth", "workspace", "basic-auth")
                      }),
                  Map.of(
                      "name",
                      "render",
                      "runAfter",
                      new Object[] {"fetch"},
                      "taskRef",
                      Map.of("name", "render-publish"),
                      "params",
                      new Object[] {
                        Map.of("name", "cluster", "value", "$(params.cluster)"),
                        Map.of("name", "node", "value", "$(params.node)")
                      },
                      "workspaces",
                      new Object[] {
                        Map.of("name", "source", "workspace", "source"),
                        Map.of("name", "maven-cache", "workspace", "maven-cache"),
                        Map.of("name", "basic-auth", "workspace", "basic-auth")
                      })
                })));
  }
}
