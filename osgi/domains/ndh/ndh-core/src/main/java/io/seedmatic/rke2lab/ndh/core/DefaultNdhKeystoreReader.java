package io.seedmatic.rke2lab.ndh.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.osgi.service.component.annotations.Component;

/**
 * The realised ndh reader — the single door to the operator's key inventory ({@code
 * .ndh-ssh.d/keys.yaml}). Like {@code worktree-core} reads git, it reads the inventory: a pure-Java
 * contact, CWD-relative (the readers run in the host JVM rooted at the worktree, where the smudge
 * filter laid the plaintext copy). The YAML is parsed per call — it is tiny, and reading fresh
 * avoids any staleness across a run.
 *
 * <p>{@link #present()} is the fail-soft gate; the accessors are fail-fast — a
 * present-but-malformed store (unparseable, sops-encrypted at rest, or a missing field) raises, a
 * defect to surface.
 */
@Component(service = NdhKeystoreReader.class)
public final class DefaultNdhKeystoreReader implements NdhKeystoreReader {

  private static final Path PATH = Path.of(".ndh-ssh.d/keys.yaml");

  @Override
  public boolean present() {
    return Files.isReadable(PATH);
  }

  @Override
  public String authorityCert(String authority) {
    return text("authorities", authority, "ca_crt");
  }

  @Override
  public String authorityDomain(String authority) {
    return text("authorities", authority, "domain");
  }

  @Override
  public String authorityPrivate(String authority) {
    return text("authorities", authority, "private");
  }

  @Override
  public String sshPrivate(String keyName) {
    return text("keys", keyName, "private");
  }

  @Override
  public String sshPublic(String keyName) {
    return text("keys", keyName, "public");
  }

  private String text(String... segments) {
    final JsonNode root = read();
    JsonNode node = root;
    for (String segment : segments) {
      node = node == null ? null : node.get(segment);
    }
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalStateException(
          "missing/empty '" + String.join(".", segments) + "' in " + PATH);
    }
    return node.asText();
  }

  private JsonNode read() {
    if (!Files.isReadable(PATH)) {
      throw new IllegalStateException(
          "ndh key-store not readable at " + PATH + " (run from the worktree root; smudged?)");
    }
    final JsonNode root;
    try {
      root = new ObjectMapper(new YAMLFactory()).readTree(PATH.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("failed to parse " + PATH, ex);
    }
    if (root.has("sops")) {
      throw new IllegalStateException(
          PATH
              + " appears sops-encrypted at rest; the worktree copy must be plaintext "
              + "(check .gitattributes declares filter=sops-yaml and the smudge ran).");
    }
    return root;
  }
}
