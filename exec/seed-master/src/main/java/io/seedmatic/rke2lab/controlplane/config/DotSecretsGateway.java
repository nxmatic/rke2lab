package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The host realisation of {@link SecretsGateway} — the flat host owns {@code .secrets} (the {@code
 * ConfigLoader} family reads it; this writes it), and publishes this into the framework it grew so
 * an in-container scion can read/write an anchor through the seam without any {@code .secrets}
 * logic crossing a realm boundary.
 *
 * <p>Read navigates the smudged (plaintext) {@code .secrets} YAML by dotted path and returns the
 * subtree as JSON. Write is a SURGICAL top-level-block upsert: it rewrites only that block,
 * preserving every other block and its {@code # sops:encrypted} comments verbatim — a whole-file
 * YAML round-trip would strip those comments and silently un-encrypt the other secrets. Leaves
 * named in {@code encryptedLeaves} get a {@code # sops:encrypted} comment (the git sops filter
 * encrypts them at commit); identifiers left out stay in the clear, matching the file's convention.
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

  @Override
  public void write(String key, String json, Set<String> encryptedLeaves) {
    if (key.contains(".")) {
      throw new IllegalArgumentException("SecretsGateway writes top-level blocks only, not " + key);
    }
    try {
      final JsonNode block = JSON.readTree(json);
      final String existing =
          Files.isReadable(secretsFile)
              ? Files.readString(secretsFile, StandardCharsets.UTF_8)
              : "";
      final String spliced = splice(existing, key, renderBlock(key, block, encryptedLeaves));
      Files.writeString(secretsFile, spliced, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write the secrets file at " + secretsFile, ex);
    }
  }

  private static List<String> renderBlock(String key, JsonNode block, Set<String> encryptedLeaves) {
    final List<String> lines = new ArrayList<>();
    lines.add(key + ":");
    block
        .properties()
        .forEach(
            entry -> {
              final String name = entry.getKey();
              final JsonNode value = entry.getValue();
              if (encryptedLeaves.contains(name)) {
                lines.add("  # sops:encrypted");
              }
              if (value.isTextual() && value.asText().contains("\n")) {
                lines.add("  " + name + ": |");
                for (final String pemLine : value.asText().stripTrailing().split("\n", -1)) {
                  lines.add("    " + pemLine);
                }
              } else if (value.isTextual()) {
                lines.add("  " + name + ": " + quote(value.asText()));
              } else {
                lines.add("  " + name + ": " + value.asText());
              }
            });
    return lines;
  }

  /**
   * Replace the existing top-level {@code key:} block (or append it) — every other line verbatim.
   */
  private static String splice(String existing, String key, List<String> block) {
    final List<String> in =
        existing.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(existing.split("\n", -1)));
    final List<String> out = new ArrayList<>();
    boolean replaced = false;
    int i = 0;
    while (i < in.size()) {
      final String line = in.get(i);
      if (line.equals(key + ":") || line.startsWith(key + ":")) {
        out.addAll(block);
        replaced = true;
        i++;
        while (i < in.size()
            && (in.get(i).isBlank() || Character.isWhitespace(in.get(i).charAt(0)))) {
          i++;
        }
        continue;
      }
      out.add(line);
      i++;
    }
    if (!replaced) {
      while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
        out.remove(out.size() - 1);
      }
      out.addAll(block);
      out.add("");
    }
    return String.join("\n", out);
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
