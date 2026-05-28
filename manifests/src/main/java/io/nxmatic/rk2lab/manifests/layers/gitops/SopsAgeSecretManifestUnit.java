package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.Chart;
import org.cdk8s.JsonPatch;

/**
 * Manifest unit that creates the SOPS age key Secret for Flux decryption.
 *
 * <p>The Secret contains the private age key that Flux uses to decrypt SOPS-encrypted resources
 * (e.g., cloud-init Secrets in Phase 2). The corresponding public key is committed to {@code
 * gitops/clusters/<cluster>/.sops.yaml}.
 *
 * <p><b>Operator setup required:</b>
 *
 * <ol>
 *   <li>Generate age keypair: {@code age-keygen -o cluster-age.key}
 *   <li>Extract public key from file (line starting with "# public key:")
 *   <li>Create {@code gitops/clusters/<cluster>/.sops.yaml} with public key
 *   <li>Store private key in {@code .secrets} under {@code flux.ageKey}
 *   <li>Encrypt {@code .secrets} with SOPS
 *   <li>Commit {@code .sops.yaml} to repository
 * </ol>
 *
 * <p>The manifest unit reads the private key from {@code .secrets} and creates the Secret in {@code
 * flux-system} namespace.
 *
 * <p><b>Note:</b> This is the Stage A bootstrap - the Secret is applied at master bootstrap time.
 * After that, Flux can decrypt SOPS-encrypted resources.
 */
public final class SopsAgeSecretManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "gitops/sops-age";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "sops-age");

  public SopsAgeSecretManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    try {
      final Path secretsFile = Path.of(".secrets");
      final String ageKey = readAgeKeyFromSecrets(secretsFile);
      createSopsAgeSecret(chart, ageKey);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to materialize SOPS age secret", ex);
    }
  }

  private void createSopsAgeSecret(Chart chart, String ageKey) {
    ApiObject secret =
        new ApiObject(
            chart,
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
                Base64.getEncoder().encodeToString(ageKey.getBytes(StandardCharsets.UTF_8)))));
  }

  private String readAgeKeyFromSecrets(Path secretsFile) throws Exception {
    if (!Files.exists(secretsFile)) {
      throw new IllegalStateException(
          "Secrets file not found at "
              + secretsFile
              + " - cannot read age key. "
              + "Run 'age-keygen -o cluster-age.key' and add private key to .secrets under flux.ageKey");
    }

    final String secretsContent = Files.readString(secretsFile, StandardCharsets.UTF_8);
    final java.util.regex.Pattern keyPattern =
        java.util.regex.Pattern.compile(
            "flux:\\s*\\n(?:.*\\n)*?\\s*ageKey:\\s*\\|\\s*\\n((?:\\s+.*\\n)+)",
            java.util.regex.Pattern.MULTILINE);
    final java.util.regex.Matcher matcher = keyPattern.matcher(secretsContent);
    if (!matcher.find()) {
      throw new IllegalStateException(
          "flux.ageKey not found in "
              + secretsFile
              + " - expected YAML block scalar with age private key. "
              + "Generate with: age-keygen -o cluster-age.key, then add to .secrets");
    }

    final String keyBlock = matcher.group(1);
    final String[] lines = keyBlock.split("\\n");
    final StringBuilder key = new StringBuilder();
    for (String line : lines) {
      key.append(line.trim()).append("\n");
    }
    return key.toString().trim();
  }
}
