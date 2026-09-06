package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Manifest unit that creates the Incus identity secret for Cluster API Provider Incus (CAPN).
 *
 * <p>The {@code capn-provider} identity material (server address, server cert, client cert, client
 * key) is resolved into the request context by the in-container {@code ManifestSynthesisScenario}
 * and handed across the frontier as {@link IncusIdentityMaterial} on the synthesis request. This
 * unit only RENDERS the {@code <cluster>-incus-identity} Secret in {@code capn-system} from that
 * material — it never reads a host file or classpath resource itself. The Secret's {@code data} is
 * base64-encoded here, as Kubernetes requires.
 */
public final class IncusIdentitySecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER_API + "/incus-identity";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "incus-identity");

  public IncusIdentitySecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String clusterName = ManifestSynthesisContext.current().bootstrapIdentity().clusterName();
    final Optional<IncusIdentityMaterial> maybeMaterial =
        ManifestSynthesisContext.current().incusIdentity();

    // Skip in ephemeral/test mode: no real identity (no cluster, or seed-master supplied no
    // material).
    if (BootstrapIdentity.UNKNOWN.equals(clusterName) || maybeMaterial.isEmpty()) {
      return;
    }
    final IncusIdentityMaterial material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-incus-identity",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterName + "-incus-identity")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|capn-system|" + clusterName + "-incus-identity"))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "server", base64(material.serverAddress()),
                "server-crt", base64(material.serverCert()),
                "client-crt", base64(material.clientCert()),
                "client-key", base64(material.clientKey()))));
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
