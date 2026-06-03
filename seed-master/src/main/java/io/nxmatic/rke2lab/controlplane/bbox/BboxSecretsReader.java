package io.nxmatic.rke2lab.controlplane.bbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Read the bbox endpoint coordinates from the worktree {@code .secrets} YAML.
 *
 * <p>The {@code .secrets} file is sops-encrypted at rest but the rke2lab repo declares a {@code
 * filter=sops-yaml} smudge filter in {@code .gitattributes}, so the worktree copy is plaintext
 * during normal operation. This reader assumes that contract — if the file looks encrypted (top-
 * level {@code sops:} key present, or no plain {@code lan.bbox} block), it raises a clear error
 * rather than silently passing an {@code ENC[...]} blob to the bbox login flow.
 *
 * <p>Schema (worktree plaintext):
 *
 * <pre>
 * lan:
 *   bbox:
 *     uri: https://mabbox.bytel.fr/
 *     # sops:encrypted
 *     password: ...
 * </pre>
 */
public final class BboxSecretsReader {

  /** Coordinates returned together to avoid two passes over the YAML for related fields. */
  public record BboxCoordinates(URI uri, String password) {}

  /** Canonical key path inside {@code .secrets} for the bbox admin password. */
  public static final List<String> BBOX_PASSWORD_KEY_PATH = List.of("lan", "bbox", "password");

  /** Canonical key path inside {@code .secrets} for the bbox base URI. */
  public static final List<String> BBOX_URI_KEY_PATH = List.of("lan", "bbox", "uri");

  private static final String SECRETS_FILENAME = ".secrets";

  private BboxSecretsReader() {}

  /**
   * Resolve the bbox base URI and admin password from {@code <worktree>/.secrets} in one pass.
   *
   * @throws IllegalStateException if the file is missing, looks encrypted, or either key is absent
   *     / blank.
   */
  public static BboxCoordinates readBboxCoordinates(Path worktreePath) {
    final ObjectNode root = readPlaintextRoot(worktreePath);
    final String rawUri = readStringScalar(root, BBOX_URI_KEY_PATH, worktreePath);
    final URI uri;
    try {
      uri = URI.create(rawUri);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Invalid URI for '" + dotted(BBOX_URI_KEY_PATH) + "' in .secrets: " + rawUri, ex);
    }
    final String password = readStringScalar(root, BBOX_PASSWORD_KEY_PATH, worktreePath);
    return new BboxCoordinates(uri, password);
  }

  private static ObjectNode readPlaintextRoot(Path worktreePath) {
    final Path secretsPath = worktreePath.resolve(SECRETS_FILENAME);
    if (!Files.isReadable(secretsPath)) {
      throw new IllegalStateException(
          "Cannot read secrets file at "
              + secretsPath
              + " — is the sops-yaml smudge filter active?");
    }

    final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    final ObjectNode root;
    try {
      root = (ObjectNode) mapper.readTree(secretsPath.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse YAML from " + secretsPath, ex);
    }

    if (root.has("sops")) {
      throw new IllegalStateException(
          secretsPath
              + " appears to be sops-encrypted at rest; the worktree copy must be plaintext "
              + "(check that .gitattributes declares `filter=sops-yaml` and the smudge ran).");
    }
    return root;
  }

  private static String readStringScalar(ObjectNode root, List<String> keyPath, Path worktreePath) {
    final Path secretsPath = worktreePath.resolve(SECRETS_FILENAME);
    JsonNode node = root;
    for (String segment : keyPath) {
      if (node == null || !node.has(segment)) {
        throw new IllegalStateException(
            "Missing key '"
                + dotted(keyPath)
                + "' in "
                + secretsPath
                + " (stuck at segment '"
                + segment
                + "'); add it via `sops "
                + secretsPath
                + "`.");
      }
      node = node.get(segment);
    }

    if (node == null || !node.isTextual()) {
      throw new IllegalStateException(
          "Key '" + dotted(keyPath) + "' in " + secretsPath + " is not a string scalar.");
    }
    final String value = node.asText().trim();
    if (value.isEmpty()) {
      throw new IllegalStateException(
          "Key '" + dotted(keyPath) + "' in " + secretsPath + " is empty.");
    }
    return value;
  }

  private static String dotted(List<String> keyPath) {
    return String.join(".", keyPath);
  }
}
