// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestExplodeRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeResult;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeService;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Splits a consolidated multi-document YAML synth into one file per resource under {@code
 * <layer>/<package>/<order>-<kind>-<name>.yml}.
 *
 * <p>Replaces the old {@code bin/explode-manifests.sh} that used {@code yq} — the synth itself runs
 * in seed-bootstrap at pulumi-up time now, and we don't want a {@code yq} runtime dep.
 *
 * <p>Layer and package come from {@code kpt.dev/package-layer} and {@code kpt.dev/package-name}
 * annotations stamped by layer code; defaults match the old script ({@code default} / {@code
 * unknown}). Order prefix is determined by kind: {@code 00-} for CRDs, {@code 01-} for other
 * cluster-scoped resources (no namespace), {@code 02-} for namespace-scoped resources.
 */
public final class DefaultManifestExplodeService implements ManifestExplodeService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestExplodeService.class);

  private static final String CRD_KIND = "CustomResourceDefinition";

  @Override
  public String providerId() {
    return "default-snakeyaml-exploder";
  }

  @Override
  public ManifestExplodeResult explode(ManifestExplodeRequest request) throws IOException {
    final Path source = request.consolidatedManifestFile();
    final Path target = request.explodedTargetDir();

    if (!Files.isRegularFile(source)) {
      throw new IllegalStateException("Consolidated manifest file not found: " + source);
    }

    // Note: we do NOT wipe target here. The caller may have other content under
    // it (e.g. host/ assets in seed-bootstrap's manifestsRoot) that must survive.
    // Callers are responsible for clearing stale per-resource files before
    // invoking explode.
    Files.createDirectories(target);

    final List<Path> written = new ArrayList<>();
    final DumperOptions dumperOptions = buildDumperOptions();
    final Yaml writer = new Yaml(new Representer(dumperOptions), dumperOptions);
    final Yaml reader = new Yaml(new SafeConstructor(largeDocumentLoaderOptions()));

    final String yamlSource = Files.readString(source);
    for (Object document : reader.loadAll(yamlSource)) {
      if (!(document instanceof Map<?, ?> map)) {
        continue;
      }

      final String layer = annotation(map, "kpt.dev/package-layer", "default");
      final String pkg = annotation(map, "kpt.dev/package-name", "unknown");
      final String kind = stringField(map, "kind");
      if (kind == null) {
        continue;
      }
      final String namespace = nestedString(map, "metadata", "namespace");
      final String name = sanitizeFileSegment(nestedString(map, "metadata", "name"));
      final String order = orderPrefixFor(kind, namespace);
      final String fileName = order + "-" + kind.toLowerCase(Locale.ROOT) + "-" + name + ".yml";

      final Path packageDir = target.resolve(layer).resolve(pkg);
      Files.createDirectories(packageDir);
      final Path outFile = packageDir.resolve(fileName);

      try (StringWriter buffer = new StringWriter()) {
        writer.dump(document, buffer);
        Files.writeString(outFile, "---\n" + buffer);
      }
      written.add(outFile);
    }

    written.sort(Comparator.naturalOrder());

    LOG.info("Exploded {} resources from {} into {}", written.size(), source.getFileName(), target);

    return new ManifestExplodeResult(target, written);
  }

  private static DumperOptions buildDumperOptions() {
    final DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setSplitLines(false);
    options.setPrettyFlow(true);
    options.setIndent(2);
    return options;
  }

  private static String orderPrefixFor(String kind, String namespace) {
    if (CRD_KIND.equals(kind)) {
      return "00";
    }
    if (namespace == null || namespace.isBlank()) {
      return "01";
    }
    return "02";
  }

  private static String annotation(Map<?, ?> document, String key, String fallback) {
    final Object metadata = document.get("metadata");
    if (!(metadata instanceof Map<?, ?> metaMap)) {
      return fallback;
    }
    final Object annotations = metaMap.get("annotations");
    if (!(annotations instanceof Map<?, ?> annotationMap)) {
      return fallback;
    }
    final Object value = annotationMap.get(key);
    return value == null ? fallback : value.toString();
  }

  private static String stringField(Map<?, ?> map, String key) {
    final Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static String nestedString(Map<?, ?> map, String first, String second) {
    final Object value = map.get(first);
    if (!(value instanceof Map<?, ?> child)) {
      return null;
    }
    final Object inner = child.get(second);
    return inner == null ? null : inner.toString();
  }

  private static String sanitizeFileSegment(String value) {
    if (value == null || value.isBlank()) {
      return "unnamed";
    }
    return value.toLowerCase(Locale.ROOT).replace(':', '-').replace('/', '-');
  }

  /**
   * SnakeYaml's default 3 MiB code-point limit is too tight for the consolidated synth output: the
   * flox installer-assets ConfigMap embeds pre-locked flake/manifest locks plus a base64 NRI plugin
   * archive. Bump to 64 MiB to match the synthesizer's reader.
   */
  private static LoaderOptions largeDocumentLoaderOptions() {
    final LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(64 * 1024 * 1024);
    return options;
  }
}
