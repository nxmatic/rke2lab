package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
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
 * (e.g., cloud-init Secrets in Phase 2).
 *
 * <p><b>Implementation:</b>
 *
 * <ol>
 *   <li>Reads {@code .ndh-ssh.d/keys.yaml} (nix-darwin-home subtree, auto-decrypted by git sops
 *       filter)
 *   <li>Extracts {@code rke2-cluster} SSH private key
 *   <li>Converts SSH key to age format using {@code ssh-to-age}
 *   <li>Creates Kubernetes Secret in {@code flux-system} namespace
 * </ol>
 *
 * <p><b>Key derivation:</b> The age key is derived from the {@code rke2-cluster} SSH key managed in
 * nix-darwin-home. Public age key: {@code
 * age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak}. This same public key is added as
 * a recipient in both rke2lab and nix-darwin-home {@code .sops.yaml}, enabling both operator (via
 * git filter) and Flux (in-cluster) to decrypt the same content.
 *
 * <p><b>Single source of truth:</b> SSH keys are maintained only in nix-darwin-home, imported to
 * rke2lab via git subtree. No key duplication.
 *
 * <p><b>Runtime dependency:</b> Requires {@code ssh-to-age} CLI tool in PATH, provided by {@code
 * fleet/flox/keyhole} environment. Always run Maven builds via {@code flox activate -- ./mvnw ...}
 * to ensure availability.
 *
 * <p><b>Note:</b> This is Stage A bootstrap - the Secret is applied at master bootstrap time,
 * enabling Flux to decrypt SOPS-encrypted resources from gitops/ directory.
 */
public final class SopsAgeSecretManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/sops-age";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "sops-age");

  public SopsAgeSecretManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    try {
      final String ageKey = readAgeKeyFromSSH();
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

  /**
   * Reads the age key for Flux SOPS decryption by converting the rke2-cluster SSH key.
   *
   * <p>The rke2-cluster SSH private key is read from {@code .ndh-ssh.d/keys.yaml} (nix-darwin-home
   * subtree, auto-decrypted by git sops filter) and converted to age format using {@code
   * ssh-to-age}.
   *
   * @return age private key in standard format
   */
  private String readAgeKeyFromSSH() throws Exception {
    final Path keysYaml = Path.of(".ndh-ssh.d/keys.yaml");
    if (!Files.exists(keysYaml)) {
      throw new IllegalStateException(
          "SSH keys file not found at "
              + keysYaml
              + " - missing nix-darwin-home subtree. "
              + "Run: git subtree pull --prefix=.ndh-ssh.d nix-darwin-home split/hm-ssh.d --squash");
    }

    // Read keys.yaml (auto-decrypted by git sops filter)
    final String keysContent = Files.readString(keysYaml, StandardCharsets.UTF_8);

    // Extract rke2-cluster SSH private key
    final java.util.regex.Pattern keyPattern =
        java.util.regex.Pattern.compile(
            "rke2-cluster:.*?private:\\s*\\|-\\s*\\n((?:\\s+.*\\n)+)",
            java.util.regex.Pattern.DOTALL);
    final java.util.regex.Matcher matcher = keyPattern.matcher(keysContent);
    if (!matcher.find()) {
      throw new IllegalStateException(
          "rke2-cluster SSH key not found in "
              + keysYaml
              + " - expected 'rke2-cluster:' entry with private key");
    }

    // Extract and clean up SSH private key
    final String keyBlock = matcher.group(1);
    final String[] lines = keyBlock.split("\\n");
    final StringBuilder sshKey = new StringBuilder();
    for (String line : lines) {
      final String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        sshKey.append(trimmed).append("\n");
      }
    }

    // Convert SSH key to age format using ssh-to-age
    return convertSSHToAge(sshKey.toString().trim());
  }

  /**
   * Converts an SSH private key to age format using the {@code ssh-to-age} command.
   *
   * <p><b>Runtime dependency:</b> Requires {@code ssh-to-age} in PATH. This is satisfied by the
   * {@code fleet/flox/keyhole} environment included in the rke2lab flox manifest. Always run Maven
   * builds via {@code flox activate -- ./mvnw ...} to ensure the tool is available.
   *
   * @param sshPrivateKey SSH private key in OpenSSH format
   * @return age private key
   * @throws IllegalStateException if {@code ssh-to-age} is not found or exits with error
   */
  private String convertSSHToAge(String sshPrivateKey) throws Exception {
    final ProcessBuilder pb = new ProcessBuilder("ssh-to-age", "-private-key");
    pb.redirectErrorStream(true);

    final Process process = pb.start();
    try (final var out = process.getOutputStream()) {
      out.write(sshPrivateKey.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    final String output;
    try (final var in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    final int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException(
          "ssh-to-age failed with exit code " + exitCode + ": " + output);
    }

    return output.trim();
  }
}
