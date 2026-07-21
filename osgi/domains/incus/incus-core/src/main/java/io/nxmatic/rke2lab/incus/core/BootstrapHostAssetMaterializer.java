package io.nxmatic.rke2lab.incus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.incus.contract.host.BootstrapPaths;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetEntry;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetSlot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * The bootstrap host-asset materialization the incus PREPARE runs before the mounts bind the
 * staging roots — it realises a requirement of provisioning the instance. Incus DRIVES; manifests
 * CONTRIBUTES through the {@link HostAssetProvider} SPI ({@code @Reference(MULTIPLE)}), the first
 * explicit typed seam replacing the former implicit "manifests writes files by convention, incus
 * reads them back blind". A missing asset is now a missing provider — enumerable, not a silent gap.
 *
 * <p>The split: manifests reads its own synthesised slice and yields raw entries + a {@link
 * io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind delivery kind}; incus owns
 * the {@link HostAssetSlot}→{@link BootstrapPaths} root mapping AND the transform-and-place
 * strategy. The NoCloud seed format ({@code userData}→{@code user-data}…) lives HERE, in {@link
 * #seedDir}: it is the instance's boot need, which incus knows — the content stays generic (a bare
 * YAML read, no manifests type coupling).
 */
@Component(service = BootstrapHostAssetMaterializer.class)
public final class BootstrapHostAssetMaterializer {

  @Reference(
      cardinality = ReferenceCardinality.MULTIPLE,
      policyOption = ReferencePolicyOption.GREEDY)
  private volatile List<HostAssetProvider> providers = List.of();

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  /** The NoCloud file each cloud-config ConfigMap key unwraps to — the instance's seed format. */
  private static final Map<String, String> NOCLOUD_KEY_TO_FILE =
      Map.of("userData", "user-data", "metaData", "meta-data", "networkData", "network-config");

  /**
   * Collect every provider's contributions against the synthesised manifests tree and place each
   * into its slot root. Called by the incus PREPARE step, after the manifests synthesis wrote the
   * tree under {@code paths.manifestsRoot()} and before the mounts bind the roots — that manifests
   * root is the synthesis root each provider resolves its own slice against.
   */
  public void materialize(BootstrapPaths paths) throws IOException {
    materialize(paths, providers);
  }

  /**
   * Package-private seam: materialise a given provider set (production uses the @Reference set).
   */
  void materialize(BootstrapPaths paths, List<HostAssetProvider> providers) throws IOException {
    final Path synthesizedRoot = paths.manifestsRoot();
    for (HostAssetProvider provider : providers) {
      for (HostAssetContribution contribution : provider.contribute(synthesizedRoot)) {
        final Path root = rootFor(paths, contribution.slot());
        switch (contribution.deliveryKind()) {
          case SEED_DIR -> seedDir(contribution.entries(), root);
          case CONFIGMAP_FILES ->
              configMapFiles(
                  contribution.entries(),
                  root,
                  contribution.slot() == HostAssetSlot.SYSTEMD_SCRIPTS);
          case SHELL_ENV_FILE ->
              shellEnvFile(contribution.entries(), root, contribution.targetFile());
        }
      }
    }
  }

  private static Path rootFor(BootstrapPaths paths, HostAssetSlot slot) {
    return switch (slot) {
      case CLOUD_SEED -> paths.cloudSeedRoot();
      // The env vars land as ONE shell file in the scripts root, beside the loader that sources it.
      case ENV_CONFIG -> paths.scriptsRoot();
      case SYSTEMD_UNITS -> paths.systemdRoot();
      case SYSTEMD_SCRIPTS -> paths.scriptsRoot();
    };
  }

  /**
   * Strip the ConfigMap/Secret envelope from each entry and write the three NoCloud files, clearing
   * the seed root first so it holds EXACTLY the seed. Throws if any payload is missing — an
   * incomplete seed would boot a broken instance.
   */
  private void seedDir(List<HostAssetEntry> entries, Path seedRoot) throws IOException {
    final Map<String, String> payload = new LinkedHashMap<>();
    for (HostAssetEntry entry : entries) {
      payload.putAll(extractPayload(parse(entry.content())));
    }
    final List<String> missing =
        NOCLOUD_KEY_TO_FILE.keySet().stream()
            .filter(key -> !payload.containsKey(key))
            .sorted()
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "cloud-config contribution is missing NoCloud payloads " + missing);
    }
    Files.createDirectories(seedRoot);
    clearRegularFiles(seedRoot);
    for (Map.Entry<String, String> keyToFile : NOCLOUD_KEY_TO_FILE.entrySet()) {
      Files.writeString(
          seedRoot.resolve(keyToFile.getValue()),
          payload.get(keyToFile.getKey()),
          StandardCharsets.UTF_8);
    }
  }

  /**
   * Extract the env vars from every entry's ConfigMap/Secret and emit ONE shell file, wrapped
   * {@code set -a}…{@code set +a} so the boot sources it as auto-exported variables. Later entries
   * override earlier keys. Values are single-quoted (literal), so spaces and metacharacters survive
   * sourcing.
   */
  private void shellEnvFile(List<HostAssetEntry> entries, Path root, String targetFile)
      throws IOException {
    final Map<String, String> vars = new LinkedHashMap<>();
    for (HostAssetEntry entry : entries) {
      vars.putAll(extractPayload(parse(entry.content())));
    }
    final StringBuilder body = new StringBuilder("set -a\n");
    vars.forEach(
        (key, value) -> body.append(key).append('=').append(shellQuote(value)).append('\n'));
    body.append("set +a\n");
    Files.createDirectories(root);
    Files.writeString(root.resolve(targetFile), body.toString(), StandardCharsets.UTF_8);
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /**
   * Extract each entry's ConfigMap {@code data} and write every key as its own file under the root
   * (the key is the file's slot-relative path). Files land executable when the slot is a scripts
   * slot (systemd scripts must be runnable; unit files must not).
   */
  private void configMapFiles(List<HostAssetEntry> entries, Path root, boolean executable)
      throws IOException {
    Files.createDirectories(root);
    for (HostAssetEntry entry : entries) {
      for (Map.Entry<String, String> file : extractPayload(parse(entry.content())).entrySet()) {
        final Path target = root.resolve(file.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        if (executable) {
          makeExecutable(target);
        }
      }
    }
  }

  private Map<String, Object> parse(String yamlContent) {
    try {
      @SuppressWarnings("unchecked")
      final Map<String, Object> document = yaml.readValue(yamlContent, Map.class);
      return document == null ? Map.of() : document;
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to parse cloud-config contribution content", ex);
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

  private void clearRegularFiles(Path directory) throws IOException {
    try (Stream<Path> existing = Files.list(directory)) {
      existing
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException ex) {
                  throw new UncheckedIOException("failed to clear seed dir: " + path, ex);
                }
              });
    }
  }

  private static void makeExecutable(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException ex) {
      // Non-POSIX FS (dev only); the linux staging FS honours the bit.
    }
  }
}
