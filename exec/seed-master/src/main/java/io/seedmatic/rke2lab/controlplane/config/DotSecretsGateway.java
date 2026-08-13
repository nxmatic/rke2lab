package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The host realisation of {@link SecretsGateway} — the flat host owns {@code .secrets} (the {@code
 * ConfigLoader} family reads it) and publishes this READ door into the framework it grew, so an
 * in-container scion can rehydrate an anchor through the seam without any {@code .secrets} logic
 * crossing a realm boundary.
 *
 * <p>Read navigates the smudged (plaintext) {@code .secrets} YAML by dotted path and returns the
 * subtree as JSON. Writing {@code .secrets} is the operator's hand — the one App key is seeded by
 * hand once — so this seam is read-only.
 */
public final class DotSecretsGateway implements SecretsGateway {

  private static final YAMLMapper YAML = new YAMLMapper();
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final Path secretsFile;

  public DotSecretsGateway() {
    this(Path.of(".secrets"));
  }

  public DotSecretsGateway(Path secretsFile) {
    this.secretsFile = secretsFile;
  }

  @Override
  public Optional<String> read(String dottedPath) {
    if (!Files.isReadable(secretsFile)) {
      return Optional.empty();
    }
    try {
      JsonNode node = YAML.readTree(secretsFile.toFile());
      for (final String part : dottedPath.split("\\.")) {
        node = node == null ? null : node.path(part);
      }
      if (node == null || node.isMissingNode() || node.isNull()) {
        return Optional.empty();
      }
      return Optional.of(JSON.writeValueAsString(node));
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read the secrets file at " + secretsFile, ex);
    }
  }
}
