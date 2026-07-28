package io.nxmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.pulumi.Config;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent, section-aware reader over operator input. It delegates the WHERE-from to a {@link
 * SectionReader} and only turns a section's node into typed values: {@code optional*} returns
 * {@link Optional}; {@code require*} records a domain-tagged symptom on absence and returns a
 * placeholder, so the caller throws once via {@link #diagnoseIfIncomplete()}. Dotted section names
 * ({@code "manifests.publish"}) walk into sub-objects.
 *
 * <p>Each source is a small single-role {@link SectionReader}, composed as a DAG by the {@code
 * of(…)} factories: {@link PulumiSections} (the Pulumi stack config), {@link NestedDocument} (an
 * in-memory or parsed-YAML document), and {@link SecretJoinedSections} (the two-file join of a
 * config with {@code .secrets}). Every source speaks {@link JsonNode}, so navigation is type-safe —
 * no raw-map casts, no {@code @SuppressWarnings}.
 */
public final class ConfigLoader {

  @FunctionalInterface
  public interface SectionReader {
    Optional<JsonNode> read(String section);
  }

  private final SectionReader sectionReader;
  private final List<String> missingKeys = new ArrayList<>();

  private ConfigLoader(SectionReader sectionReader) {
    this.sectionReader = sectionReader;
  }

  public static ConfigLoader of(SectionReader sectionReader) {
    return new ConfigLoader(sectionReader);
  }

  /** Live: read top-level sections from the Pulumi stack config, walking dotted names. */
  public static ConfigLoader of(Config config) {
    return new ConfigLoader(new PulumiSections(config));
  }

  /**
   * Live over TWO documents: the Pulumi stack config AND the smudged {@code .secrets} file. A
   * coordinate section may carry a {@code secret:} JOIN meta ({@code {from: <dotted .secrets path>,
   * role: …}}) declaring which {@code .secrets} subtree feeds it; {@link SecretJoinedSections}
   * deep-merges that subtree into the section ({@code secret} LEAVES WIN — sops is the authority)
   * and strips the meta, so a consumer reads one uniform subtree, sourced identically to a
   * pure-config coordinate. Only what a {@code secret:} meta explicitly names is pulled — the other
   * {@code .secrets} subtrees (launch tokens, k8s-replicated creds) never enter the merged view. A
   * missing/unreadable {@code .secrets} yields an empty secret side (a survey without secrets falls
   * to defaults).
   */
  public static ConfigLoader of(Config config, Path secretsFile) {
    return new ConfigLoader(
        new SecretJoinedSections(new PulumiSections(config), NestedDocument.ofYaml(secretsFile)));
  }

  /** Offline/test: a root map keyed by top-level section; dotted names walk into sub-maps. */
  public static ConfigLoader ofNestedRoot(Map<String, Object> root) {
    return new ConfigLoader(NestedDocument.ofRoot(root));
  }

  /**
   * Offline/test twin of {@link #of(Config, Path)}: TWO nested roots — config keyed by coordinate +
   * secrets keyed by provider — joined by the per-coordinate {@code secret:} meta exactly as the
   * live reader joins the Pulumi config with {@code .secrets}.
   */
  public static ConfigLoader ofNestedRoots(
      Map<String, Object> configRoot, Map<String, Object> secretsRoot) {
    return new ConfigLoader(
        new SecretJoinedSections(
            NestedDocument.ofRoot(configRoot), NestedDocument.ofRoot(secretsRoot)));
  }

  // --- optional* : empty when absent/blank, no defaults here ---

  public Optional<String> optional(String section, String key) {
    return rawValue(section, key).map(String::trim).filter(value -> !value.isBlank());
  }

  public Optional<Path> optionalPath(String section, String key) {
    return optional(section, key).map(value -> Path.of(value).toAbsolutePath().normalize());
  }

  public Optional<URI> optionalUri(String section, String key) {
    return optional(section, key).map(URI::create);
  }

  public Optional<Boolean> optionalBoolean(String section, String key) {
    return optional(section, key).map(this::parseBoolean);
  }

  public Optional<Integer> optionalInt(String section, String key) {
    return optional(section, key).map(value -> Integer.parseInt(value.trim()));
  }

  public Optional<Duration> optionalDuration(String section, String key) {
    return optional(section, key).map(value -> Duration.parse(value.trim()));
  }

  /**
   * Reads a nested string→string sub-map (e.g. {@code policy.readiness.override} = {@code
   * {systemd-adapter: warning}}). Empty map when the key is absent or not an object.
   */
  public Map<String, String> stringMap(String section, String key) {
    final Optional<JsonNode> node =
        sectionReader.read(section).map(subtree -> subtree.path(key)).filter(JsonNode::isObject);
    if (node.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, String> result = new LinkedHashMap<>();
    node.get().properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    return result;
  }

  /**
   * Reads a nested string list (e.g. {@code entryGate.cleanWorktree.tolerated} = {@code
   * [".secrets", "Pulumi.dev.yaml"]}). Empty list when the key is absent or not an array.
   */
  public List<String> stringList(String section, String key) {
    final Optional<JsonNode> node =
        sectionReader.read(section).map(subtree -> subtree.path(key)).filter(JsonNode::isArray);
    if (node.isEmpty()) {
      return List.of();
    }
    final List<String> result = new ArrayList<>();
    node.get().forEach(element -> result.add(element.asText()));
    return result;
  }

  /**
   * The raw nested subtree at {@code section}, serialized verbatim as a JSON {@code String} — the
   * blind copy a host {@code AmendmentContributor} forwards by role (the {@code manifests} / {@code
   * bbox} FACET), naming no domain vocabulary: jackson coerces the yaml's string scalars into the
   * wire-record OSGi-side. Empty string when the section is absent, so the amendment falls to its
   * defaults.
   */
  public String subtreeJson(String section) {
    return sectionReader.read(section).map(JsonNode::toString).orElse("");
  }

  // --- require* : accumulate on absence, return placeholder ---

  public Path requirePath(String section, String key) {
    final Optional<Path> value = optionalPath(section, key);
    if (value.isEmpty()) {
      missingKeys.add(section + "." + key);
      return Path.of("/__missing__/" + section + "/" + key);
    }
    return value.get();
  }

  public String require(String section, String key) {
    final Optional<String> value = optional(section, key);
    if (value.isEmpty()) {
      missingKeys.add(section + "." + key);
      return "";
    }
    return value.get();
  }

  public List<String> missingKeys() {
    return List.copyOf(missingKeys);
  }

  public void diagnoseIfIncomplete() {
    if (!missingKeys.isEmpty()) {
      throw new MissingRequiredConfiguration(missingKeys);
    }
  }

  private Optional<String> rawValue(String section, String key) {
    return sectionReader
        .read(section)
        .map(node -> node.path(key))
        .filter(value -> !value.isMissingNode() && !value.isNull())
        .map(JsonNode::asText);
  }

  private boolean parseBoolean(String raw) {
    return switch (raw.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("Invalid boolean: " + raw);
    };
  }

  /**
   * A nested {@link JsonNode} document (parsed YAML or an in-memory config root), read by dotted
   * section path — the walk that resolves {@code "manifests.publish"} to its sub-object, shared by
   * every source. Owns the one mapper that turns a {@code .secrets} file or a raw config value into
   * a tree; every other source delegates its navigation here.
   */
  private record NestedDocument(JsonNode root) implements SectionReader {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    /** Parse a smudged {@code .secrets}-style YAML file, or an empty document when unreadable. */
    static NestedDocument ofYaml(Path file) {
      if (!Files.isReadable(file)) {
        return new NestedDocument(MAPPER.createObjectNode());
      }
      try {
        final JsonNode root = MAPPER.readTree(file.toFile());
        return new NestedDocument(root == null ? MAPPER.createObjectNode() : root);
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to read the secrets file at " + file, ex);
      }
    }

    /** Wrap an already-parsed section→submap root (the offline fixtures). */
    static NestedDocument ofRoot(Map<String, Object> root) {
      return new NestedDocument(MAPPER.valueToTree(root));
    }

    /** Wrap one named section's raw content (a Pulumi {@code getObject} result) as a document. */
    static NestedDocument ofSection(String name, Object content) {
      final ObjectNode root = MAPPER.createObjectNode();
      root.set(name, MAPPER.valueToTree(content));
      return new NestedDocument(root);
    }

    @Override
    public Optional<JsonNode> read(String section) {
      JsonNode current = root;
      for (String part : section.split("\\.")) {
        current = current.path(part);
      }
      return current.isObject() ? Optional.of(current) : Optional.empty();
    }
  }

  /**
   * A {@link SectionReader} over {@link com.pulumi.Config}: fetch the top-level section via {@code
   * getObject}, then let a {@link NestedDocument} do the dotted descent — no walk logic of its own.
   */
  private record PulumiSections(Config config) implements SectionReader {

    @Override
    public Optional<JsonNode> read(String section) {
      final String top = section.split("\\.")[0];
      return config
          .getObject(top, Object.class)
          .flatMap(content -> NestedDocument.ofSection(top, content).read(section));
    }
  }

  /**
   * Decorates a config {@link SectionReader}, joining a section's {@code secret:} meta with the
   * named subtree of a secrets {@link NestedDocument} — the two-file reconciliation. {@code secret}
   * leaves win on a collision (sops is the authority) and the join instruction is stripped, so the
   * consumer reads one uniform subtree. The {@code role} is read OSGi-side (the amendment role the
   * whole joined subtree is contributed under), so only the {@code secret} join instruction itself
   * is removed.
   */
  private record SecretJoinedSections(SectionReader config, NestedDocument secrets)
      implements SectionReader {

    private static final String SECRET_META = "secret";

    @Override
    public Optional<JsonNode> read(String section) {
      return config.read(section).map(this::join);
    }

    private JsonNode join(JsonNode subtree) {
      final JsonNode meta = subtree.path(SECRET_META);
      if (!meta.isObject()) {
        return subtree;
      }
      final String from = meta.path("from").asText("");
      if (from.isBlank()) {
        throw new IllegalStateException(
            "a 'secret:' join must name a non-blank 'from' .secrets path, got: " + meta);
      }
      final ObjectNode joined = ((ObjectNode) subtree).deepCopy();
      joined.remove(SECRET_META);
      secrets.read(from).ifPresent(secret -> merge(joined, secret));
      return joined;
    }

    private void merge(ObjectNode base, JsonNode overlay) {
      overlay
          .properties()
          .forEach(
              entry -> {
                final JsonNode existing = base.get(entry.getKey());
                if (existing instanceof ObjectNode existingObject
                    && entry.getValue() instanceof ObjectNode) {
                  merge(existingObject, entry.getValue());
                } else {
                  base.set(entry.getKey(), entry.getValue());
                }
              });
    }
  }
}
