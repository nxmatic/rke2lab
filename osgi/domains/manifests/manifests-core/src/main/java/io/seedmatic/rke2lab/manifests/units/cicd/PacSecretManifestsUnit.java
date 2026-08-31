package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
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
 * The {@code pipelines-as-code-secret} that configures Pipelines-as-Code with OUR baked GitHub App
 * — the CI twin of {@link io.seedmatic.rke2lab.manifests.units.gitops.GithubAppSecretManifestsUnit}
 * (which configures Flux's native App auth). Both consume the SAME {@link GithubAppMaterial} the
 * manifests scion reveals from the sealed {@code github-app} cellar case; both ride the {@code
 * NODE_BOOTSTRAP} lane (real App key, never on the reconciled branch). PaC reads this secret to act
 * AS the App: verify webhooks, clone, set commit statuses, and mint the {@code git_auth_secret} it
 * injects into each PipelineRun.
 *
 * <p>Keys (the names PaC reads for a GitHub App):
 *
 * <ul>
 *   <li>{@code github-application-id} — the App id.
 *   <li>{@code github-private-key} — the App's private key (PEM), from which PaC mints installation
 *       tokens.
 * </ul>
 *
 * <p><b>{@code webhook.secret} is not rendered here yet.</b> It is the App-webhook HMAC PaC
 * validates incoming payloads against — inert until the App's webhook is pointed at the PaC funnel
 * (the operator App-extension ceremony, a later step). PaC authenticates as the App with the id +
 * key alone; the webhook secret pairs with wiring the webhook. Its value is the operator-chosen
 * {@code github.webhook.token} — already revealed to synthesis inside {@code
 * ReplicatorSourceSecretsMaterial} (the {@code flux-webhook-token} source) — so when it is added it
 * draws from that single source, not a duplicate reveal.
 *
 * <p>Namespace {@code tekton-pipelines}: the Tekton operator (profile {@code all}, {@code
 * targetNamespace: tekton-pipelines}) installs the PaC controller there, so its secret lives there
 * too (twin of {@link PacWebhookManifestsUnit}'s funnel backend).
 *
 * <p>Absence — no App credentials sealed (ephemeral / survey runs) — is an empty {@code
 * Optional<GithubAppMaterial>} and the unit renders nothing (the honest local-render skip, as its
 * gitops twin).
 */
public final class PacSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/pac-secret";

  private static final String NAMESPACE = "tekton-pipelines";

  private static final String SECRET_NAME = "pipelines-as-code-secret";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "pac-secret", true);

  public PacSecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<GithubAppMaterial> maybeMaterial =
        ManifestSynthesisContext.current().githubApp();
    if (maybeMaterial.isEmpty()) {
      return;
    }
    final GithubAppMaterial material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-pipelines-as-code",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SECRET_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + NAMESPACE + "|" + SECRET_NAME))
                        .build())
                .build());

    final Base64.Encoder b64 = Base64.getEncoder();
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "github-application-id",
                b64.encodeToString(material.appId().getBytes(StandardCharsets.UTF_8)),
                "github-private-key",
                b64.encodeToString(material.privateKeyPem().getBytes(StandardCharsets.UTF_8)))));
  }
}
