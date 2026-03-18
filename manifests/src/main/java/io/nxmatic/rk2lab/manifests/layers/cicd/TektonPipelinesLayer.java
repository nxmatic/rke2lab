// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TektonPipelinesLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "cicd/tekton-pipelines/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-pipelines");

  public TektonPipelinesLayer(final Construct scope, final String id) {
    super(scope, id);

    createTektonOperatorNamespace();
    createReplicatedSecret(
        "tekton-git-auth", "kubernetes.io/basic-auth", "kube-system/tekton-git-auth");
    createReplicatedSecret(
        "tekton-docker-config",
        "kubernetes.io/dockerconfigjson",
        "kube-system/tekton-docker-config");
    createTektonConfig();
  }

  private void createTektonOperatorNamespace() {
    new ApiObject(
        this,
        "namespace-tekton-operator",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("tekton-operator")
                    .annotations(
                        packageProfile.packageAnnotations("|Namespace|default|tekton-operator"))
                    .build())
            .build());
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
    secret.addJsonPatch(JsonPatch.add("/type", type));
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
