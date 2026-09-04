// @codebase
package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TektonPipelinesManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/tekton-pipelines";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-pipelines");

  public TektonPipelinesManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String operatorVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.TEKTON_OPERATOR);

    // Bundle the upstream operator release (Namespace, CRDs, RBAC, Service, Deployment,
    // ConfigMaps, webhooks). The bundle ships its own `tekton-operator` Namespace, so we no
    // longer create one separately. Version is resolved from ComponentVersions; the matching
    // release-<version>.yaml must exist under src/main/resources/upstream/cicd/tekton-operator/
    // — the build will fail fast if it doesn't.
    final String operatorReleaseResource =
        "/upstream/cicd/tekton-operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(scope, operatorReleaseResource, packageProfile, context.yaml());

    // The operator CREATES targetNamespace (tekton-pipelines) only when it reconciles TektonConfig,
    // but our replicated Secrets target it in the same layer. Pre-create it here so Flux — which
    // applies Namespaces before namespaced resources — lands the Secrets; the operator then adopts
    // the existing namespace as its targetNamespace.
    createTargetNamespace(scope);
    createReplicatedSecret(
        scope,
        "tekton-git-auth",
        "kubernetes.io/basic-auth",
        "rke2lab-replicator-source/tekton-git-auth");
    createReplicatedSecret(
        scope,
        "tekton-docker-config",
        "kubernetes.io/dockerconfigjson",
        "rke2lab-replicator-source/tekton-docker-config");
    createTektonConfig(scope);
  }

  private void createTargetNamespace(final Construct scope) {
    new ApiObject(
        scope,
        "namespace-tekton-pipelines",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("tekton-pipelines")
                    .annotations(packageProfile.packageAnnotations("|Namespace||tekton-pipelines"))
                    .build())
            .build());
  }

  private void createReplicatedSecret(
      final Construct scope, final String name, final String type, final String replicateFrom) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace("tekton-pipelines")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|tekton-pipelines|" + name,
                                Map.of("replicator.v1.mittwald.de/replicate-from", replicateFrom)))
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .build())
                .build());

    // Valid placeholders until the mittwald replicator overwrites the data from the source secret:
    // a dockerconfigjson value MUST parse as JSON (an empty string fails apiserver validation with
    // "unexpected end of JSON input"), so seed the canonical empty docker config.
    Map<String, String> emptyData =
        switch (type) {
          case "kubernetes.io/basic-auth" -> Map.of("username", "", "password", "");
          case "kubernetes.io/dockerconfigjson" -> Map.of(".dockerconfigjson", "{\"auths\":{}}");
          default -> Map.of();
        };

    secret.addJsonPatch(JsonPatch.add("/type", type), JsonPatch.add("/stringData", emptyData));
  }

  private void createTektonConfig(final Construct scope) {
    ApiObject config =
        new ApiObject(
            scope,
            "tektonconfig-config",
            ApiObjectProps.builder()
                .apiVersion("operator.tekton.dev/v1alpha1")
                .kind("TektonConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.tekton.dev|TektonConfig|default|config"))
                        .build())
                .build());

    config.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "profile",
                "all",
                "targetNamespace",
                "tekton-pipelines",
                // coschedule=pipelineruns (default is `workspaces`): the affinity assistant pins a
                // whole PipelineRun's pods to one node instead of one-assistant-per-PVC-workspace.
                // The default caps a TaskRun at ONE PVC workspace ("more than one
                // PersistentVolumeClaim is bound"); our render pipeline's build task needs TWO (the
                // shared `source` PVC passed fetch->render + the persistent `maven-cache` PVC). On
                // a
                // single-node cluster this changes nothing scheduling-wise; the operator merges its
                // other pipeline defaults.
                "pipeline",
                Map.of("coschedule", "pipelineruns"),
                // The TektonConfig CRD requires result.{disabled,is_external_db,options} and
                // pruner.disabled (no schema defaults) — a bare result.disabled fails dry-run.
                // Results feature off (disabled) with an internal-DB posture + empty options;
                // pruner active (disabled=false) with our keep/schedule.
                "result",
                Map.of("disabled", true, "is_external_db", false, "options", Map.of()),
                "pruner",
                Map.of(
                    "disabled",
                    false,
                    "resources",
                    List.of("taskrun", "pipelinerun"),
                    "keep",
                    100,
                    "schedule",
                    "0 8 * * *"),
                // Extend the git_auth installation token PaC mints so it can read a PRIVATE flake
                // input the render pulls (seedmatic/claude-hub, transitively via ndh). By default
                // secret-github-app-token-scoped=true scopes the token to the payload repo
                // (rke2lab)
                // only → nix 404s on the private claude-hub. scope-extra-repos widens it to
                // rke2lab + claude-hub (least-privilege vs token-scoped=false = the whole
                // installation). The operator writes these settings into the operator-managed
                // pipelines-as-code ConfigMap (a direct edit would be reverted). The operand is
                // "openshift-pipeline-as-code" even on k8s, so the config path is
                // platforms.openshift.
                "platforms",
                Map.of(
                    "openshift",
                    Map.of(
                        "pipelinesAsCode",
                        Map.of(
                            "settings",
                            Map.of(
                                "secret-github-app-scope-extra-repos",
                                "seedmatic/claude-hub")))))));
  }
}
