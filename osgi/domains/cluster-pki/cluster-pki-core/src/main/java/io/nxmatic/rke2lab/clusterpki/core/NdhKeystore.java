package io.nxmatic.rke2lab.clusterpki.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the ndh SSH/TLS key inventory ({@code .ndh-ssh.d/keys.yaml}) — the operator's key-store,
 * the SAME contact {@code SopsAgeMaterialResolver} makes for the gitops age key, so the read is a
 * structured-YAML navigation (no regex) and the worktree copy MUST be the smudged plaintext. CWD-
 * relative: the seal scion runs in the host JVM whose working directory is the worktree root.
 *
 * <p>It exposes exactly the three fields the seal needs — the {@code mammoth-skate-tls} TLS root's
 * certificate + private key (to root the cluster CA on), and the {@code rke2-cluster} SSH private
 * key (which ssh-to-age turns into the cluster's age identity). Nothing else of the key-store's
 * private material is touched. Fail-soft on absence (an ephemeral run has no key-store), fail-fast
 * on malformation (a present-but-broken store is a defect to surface).
 */
public final class NdhKeystore {

  /**
   * The tls-authority the cluster CA is rooted on — the single source, matching {@code .sops.yaml}.
   */
  public static final String TLS_AUTHORITY = "mammoth-skate-tls";

  private static final Path DEFAULT_PATH = Path.of(".ndh-ssh.d/keys.yaml");
  private static final String SSH_KEY = "rke2-cluster";

  private final Path path;
  private final JsonNode root;

  public NdhKeystore() {
    this(DEFAULT_PATH);
  }

  public NdhKeystore(Path keysYaml) {
    this.path = keysYaml;
    if (!Files.isReadable(keysYaml)) {
      throw new IllegalStateException(
          "ndh key-store not readable at " + keysYaml + " (run from the worktree root; smudged?)");
    }
    try {
      this.root = new ObjectMapper(new YAMLFactory()).readTree(keysYaml.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("failed to parse " + keysYaml, ex);
    }
    if (root.has("sops")) {
      throw new IllegalStateException(
          keysYaml
              + " appears sops-encrypted at rest; the worktree copy must be plaintext "
              + "(check .gitattributes declares filter=sops-yaml and the smudge ran).");
    }
  }

  /**
   * Whether a key-store is present + readable at the default path (fail-soft gate for the scion).
   */
  public static boolean present() {
    return Files.isReadable(DEFAULT_PATH);
  }

  /** The {@code mammoth-skate-tls} root certificate (PEM, {@code BEGIN CERTIFICATE}). */
  public String rootCertPem() {
    return text("authorities", TLS_AUTHORITY, "ca_crt");
  }

  /** The {@code mammoth-skate-tls} root private key (PEM or OpenSSH — the generator reads both). */
  public String rootKeyPem() {
    return text("authorities", TLS_AUTHORITY, "private");
  }

  /**
   * The {@code rke2-cluster} SSH private key — ssh-to-age turns it into the cluster age identity.
   */
  public String rke2ClusterPrivateKey() {
    return text("keys", SSH_KEY, "private");
  }

  private String text(String... segments) {
    JsonNode node = root;
    for (String segment : segments) {
      node = node == null ? null : node.get(segment);
    }
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalStateException(
          "missing/empty '" + String.join(".", segments) + "' in " + path);
    }
    return node.asText();
  }
}
