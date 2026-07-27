package io.nxmatic.rke2lab.manifests.units.gitops;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
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
 * Manifest unit that creates the SOPS age key Secret for Flux decryption.
 *
 * <p>The Secret contains the private age key that Flux uses to decrypt SOPS-encrypted resources
 * (e.g., cloud-init Secrets in Phase 2).
 *
 * <p><b>The unit only RENDERS.</b> The age key is a prerequisite, resolved upstream by the
 * synthesis service's pre-synthesis step (read the {@code rke2-cluster} SSH key, convert it via the
 * {@code ssh-to-age} edge) and handed in as {@link SopsAgeMaterial} on {@link
 * ManifestSynthesisContext} — the same channel as {@code IncusIdentityMaterial} / {@code
 * BootstrapIdentity}. This unit never reads a host file or shells a tool itself; it embeds the key
 * into the Secret, base64-encoded as Kubernetes requires.
 *
 * <p><b>Key derivation:</b> the age key is derived from the {@code rke2-cluster} SSH key managed in
 * nix-darwin-home. Public age key: {@code
 * age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak}. This same public key is added as
 * a recipient in both rke2lab and nix-darwin-home {@code .sops.yaml}, enabling both operator (via
 * git filter) and Flux (in-cluster) to decrypt the same content.
 *
 * <p><b>Note:</b> This is Stage A bootstrap - the Secret is applied at master bootstrap time,
 * enabling Flux to decrypt SOPS-encrypted resources from gitops/ directory.
 */
public final class SopsAgeSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/sops-age";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "sops-age");

  public SopsAgeSecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<SopsAgeMaterial> maybeMaterial =
        ManifestSynthesisContext.current().sopsAgeMaterial();

    // Skip in ephemeral/test mode: no SSH key-store was present, so the pre-synthesis step supplied
    // no real age key.
    if (maybeMaterial.isEmpty()) {
      return;
    }
    final SopsAgeMaterial material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-sops-age",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("sops-age")
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations("|Secret|flux-system|sops-age"))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "age.agekey",
                Base64.getEncoder()
                    .encodeToString(material.ageKey().getBytes(StandardCharsets.UTF_8)))));
  }
}
