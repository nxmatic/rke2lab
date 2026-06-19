// @codebase
package io.nxmatic.rke2lab.manifests.units.cicd;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.nxmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
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
        ManifestSynthesisContext.current().componentVersions().tektonOperator();

    // Bundle the upstream operator release (Namespace, CRDs, RBAC, Service, Deployment,
    // ConfigMaps, webhooks). The bundle ships its own `tekton-operator` Namespace, so we no
    // longer create one separately. Version is resolved from ComponentVersions; the matching
    // release-<version>.yaml must exist under src/main/resources/upstream/cicd/tekton-operator/
    // — the build will fail fast if it doesn't.
    final String operatorReleaseResource =
        "/upstream/cicd/tekton-operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(scope, operatorReleaseResource, packageProfile);

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

    Map<String, String> emptyData =
        switch (type) {
          case "kubernetes.io/basic-auth" -> Map.of("username", "", "password", "");
          case "kubernetes.io/dockerconfigjson" -> Map.of(".dockerconfigjson", "");
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
                "result",
                Map.of("disabled", true),
                "pruner",
                Map.of(
                    "resources",
                    List.of("taskrun", "pipelinerun"),
                    "keep",
                    100,
                    "schedule",
                    "0 8 * * *"))));
  }
}
