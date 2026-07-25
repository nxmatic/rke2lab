package io.nxmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulumi.Config;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Fluent, section-aware reader over Pulumi config; the only class that touches {@link
 * com.pulumi.Config}. Reads each top-level section once as a {@code Map} (nested YAML), pulling
 * keys from it. Dotted section names ({@code "manifests.publish"}) walk into sub-maps. {@code
 * optional*} returns {@link Optional}; {@code require*} records a domain-tagged symptom on absence
 * and returns a placeholder, so the caller throws once via {@link #diagnoseIfIncomplete()}.
 */
public final class ConfigLoader {

  @FunctionalInterface
  public interface SectionReader {
    Optional<Map<String, Object>> read(String section);
  }

  private final SectionReader sectionReader;
  private final List<String> missingKeys = new ArrayList<>();

  private ConfigLoader(SectionReader sectionReader) {
    this.sectionReader = sectionReader;
  }

  public static ConfigLoader of(SectionReader sectionReader) {
    return new ConfigLoader(sectionReader);
  }

  /** Live: read top-level sections via Pulumi getObject, walking dotted names into sub-maps. */
  @SuppressWarnings({"unchecked", "null"})
  public static ConfigLoader of(Config config) {
    return new ConfigLoader(
        section -> {
          final String[] parts = section.split("\\.");
          final Optional<Map<String, Object>> top =
              (Optional<Map<String, Object>>) (Optional<?>) config.getObject(parts[0], Map.class);
          return walk(top, parts);
        });
  }

  /** Offline/test: a root map keyed by top-level section; dotted names walk into sub-maps. */
  public static ConfigLoader ofNestedRoot(Map<String, Object> root) {
    return new ConfigLoader(
        section -> walk(asMap(root.get(section.split("\\.")[0])), section.split("\\.")));
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> walk(
      Optional<Map<String, Object>> top, String[] parts) {
    Object current = top.orElse(null);
    for (int i = 1; i < parts.length && current instanceof Map; i++) {
      current = ((Map<String, Object>) current).get(parts[i]);
    }
    return current instanceof Map ? Optional.of((Map<String, Object>) current) : Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> asMap(@Nullable Object value) {
    return value instanceof Map ? Optional.of((Map<String, Object>) value) : Optional.empty();
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
    return optional(section, key).map(ConfigLoader::parseBoolean);
  }

  public Optional<Integer> optionalInt(String section, String key) {
    return optional(section, key).map(value -> Integer.parseInt(value.trim()));
  }

  public Optional<Duration> optionalDuration(String section, String key) {
    return optional(section, key).map(value -> Duration.parse(value.trim()));
  }

  /**
   * Reads a nested string→string sub-map (e.g. {@code policy.readiness.override} = {@code
   * {systemd-adapter: warning}}). Empty map when the key is absent. Values are stringified so a
   * non-string scalar degrades to its text form rather than crashing.
   */
  @SuppressWarnings("unchecked")
  public Map<String, String> stringMap(String section, String key) {
    final Object value = sectionReader.read(section).map(map -> map.get(key)).orElse(null);
    if (!(value instanceof Map)) {
      return Map.of();
    }
    final LinkedHashMap<String, String> result = new LinkedHashMap<>();
    ((Map<String, Object>) value)
        .forEach((entryKey, entryValue) -> result.put(entryKey, String.valueOf(entryValue)));
    return result;
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The raw nested subtree at {@code section}, serialized verbatim as a JSON {@code String} — the
   * blind copy a host {@code AmendmentContributor} forwards by role (the {@code manifests} FACET),
   * naming no domain vocabulary: jackson coerces the yaml's string scalars into the wire-record
   * OSGi-side. Empty string when the section is absent, so the amendment falls to its defaults.
   */
  public String subtreeJson(String section) {
    final Optional<Map<String, Object>> subtree = sectionReader.read(section);
    if (subtree.isEmpty()) {
      return "";
    }
    try {
      return JSON.writeValueAsString(subtree.get());
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("failed to serialize config subtree '" + section + "'", ex);
    }
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
    return sectionReader.read(section).map(map -> map.get(key)).map(String::valueOf);
  }

  private static boolean parseBoolean(String raw) {
    return switch (raw.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("Invalid boolean: " + raw);
    };
  }
}
