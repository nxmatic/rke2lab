package io.nxmatic.rke2lab.incus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Unwraps the synthesised {@code cloud-config} ConfigMap into the NoCloud seed the incus instance
 * reads at first boot — {@code user-data}/{@code meta-data}/{@code network-config}. The three
 * values are authored in FINAL form by {@code manifests}+{@code netplan} (templates keyed {@code
 * userData}/{@code metaData}/{@code networkData}) and synthesised into a ConfigMap on the staging
 * FS; this writer only STRIPS the ConfigMap/Secret envelope and writes the values byte-for-byte —
 * there is NO format transcode. A beat of the incus PREPARE (§ provisioning-slice delta #2): the
 * CONTENT is manifests'; the unwrap-to-nocloud serves the instance, so it is incus's.
 *
 * <p>The parse is a GENERIC YAML read into a {@code Map} (no {@code manifests-contract} coupling —
 * the same {@code yaml.read(path, Map.class)} the old {@code ManifestDocumentService} did): a
 * ConfigMap yields its {@code data}; a Secret yields its {@code stringData} plus base64-decoded
 * {@code data}. The values are matched by their ConfigMap keys ({@code userData} …) and land under
 * the NoCloud file names ({@code user-data} …).
 */
public final class NocloudSeedWriter {

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  /** The NoCloud file each ConfigMap {@code data} key unwraps to. */
  private static final Map<String, String> KEY_TO_FILE =
      Map.of(
          "userData", "user-data",
          "metaData", "meta-data",
          "networkData", "network-config");

  /**
   * Read every YAML document under {@code runtimeCloudConfigRoot}, unwrap the envelope, and write
   * the three NoCloud files into {@code cloudSeedRoot} (created if absent, regular files cleared
   * first). Throws if any of the three payloads is missing — an incomplete seed would boot a broken
   * instance.
   */
  public void unwrap(Path runtimeCloudConfigRoot, Path cloudSeedRoot) {
    final Map<String, String> payload = new LinkedHashMap<>();
    for (Path yamlSource : listYamlSources(runtimeCloudConfigRoot)) {
      payload.putAll(extractPayload(parse(yamlSource)));
    }
    final List<String> missing =
        KEY_TO_FILE.keySet().stream().filter(key -> !payload.containsKey(key)).sorted().toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "cloud-config source is missing NoCloud payloads "
              + missing
              + ": "
              + runtimeCloudConfigRoot);
    }
    write(cloudSeedRoot, payload);
  }

  private List<Path> listYamlSources(Path root) {
    try (Stream<Path> entries = Files.list(root)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                final String name = path.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml");
              })
          .sorted()
          .toList();
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to list cloud-config sources: " + root, ex);
    }
  }

  private Map<String, Object> parse(Path yamlSource) {
    try {
      @SuppressWarnings("unchecked")
      final Map<String, Object> document = yaml.readValue(yamlSource.toFile(), Map.class);
      return document;
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to parse cloud-config YAML: " + yamlSource, ex);
    }
  }

  private Map<String, String> extractPayload(Map<String, Object> document) {
    final String kind = String.valueOf(document.getOrDefault("kind", ""));
    if ("ConfigMap".equals(kind)) {
      return stringMap(document.get("data"));
    }
    if (!"Secret".equals(kind)) {
      return Map.of();
    }
    final Map<String, String> payload = new LinkedHashMap<>(stringMap(document.get("stringData")));
    stringMap(document.get("data"))
        .forEach(
            (key, value) ->
                payload.computeIfAbsent(
                    key,
                    ignored ->
                        new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)));
    return payload;
  }

  private Map<String, String> stringMap(@Nullable Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    final LinkedHashMap<String, String> result = new LinkedHashMap<>();
    map.forEach(
        (key, mapped) -> {
          final String name = key == null ? "" : key.toString();
          if (!name.isBlank()) {
            result.put(name, mapped == null ? "" : mapped.toString());
          }
        });
    return result;
  }

  private void write(Path cloudSeedRoot, Map<String, String> payload) {
    try {
      Files.createDirectories(cloudSeedRoot);
      clearRegularFiles(cloudSeedRoot);
      for (Map.Entry<String, String> entry : KEY_TO_FILE.entrySet()) {
        Files.writeString(
            cloudSeedRoot.resolve(entry.getValue()),
            payload.get(entry.getKey()),
            StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write NoCloud seed into: " + cloudSeedRoot, ex);
    }
  }

  private void clearRegularFiles(Path directory) throws IOException {
    try (Stream<Path> existing = Files.list(directory)) {
      existing
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException ex) {
                  throw new UncheckedIOException("failed to clear NoCloud seed: " + path, ex);
                }
              });
    }
  }
}
