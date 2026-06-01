// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TektonPipelinesLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-pipelines");

  public TektonPipelinesLayer(
      final Construct scope, final String id, final String operatorVersion) {
    super(scope, id);

    // Bundle the upstream operator release (Namespace, CRDs, RBAC, Service, Deployment,
    // ConfigMaps, webhooks). The bundle ships its own `tekton-operator` Namespace, so we no
    // longer create one separately. Version is resolved from ComponentVersions; the matching
    // release-<version>.yaml must exist under src/main/resources/upstream/cicd/tekton-operator/
    // — the build will fail fast if it doesn't.
    final String operatorReleaseResource =
        "/upstream/cicd/tekton-operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(this, operatorReleaseResource, packageProfile);

    createReplicatedSecret(
        "tekton-git-auth", "kubernetes.io/basic-auth", "rke2lab-replicator-source/tekton-git-auth");
    createReplicatedSecret(
        "tekton-docker-config",
        "kubernetes.io/dockerconfigjson",
        "rke2lab-replicator-source/tekton-docker-config");
    createTektonConfig();
  }

  private void createReplicatedSecret(
      final String name, final String type, final String replicateFrom) {
    ApiObject secret =
        new ApiObject(
            this,
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
    secret.addJsonPatch(
        JsonPatch.add("/type", type), JsonPatch.add("/stringData", Map.of("_placeholder", "")));
  }

  private void createTektonConfig() {
    ApiObject config =
        new ApiObject(
            this,
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
