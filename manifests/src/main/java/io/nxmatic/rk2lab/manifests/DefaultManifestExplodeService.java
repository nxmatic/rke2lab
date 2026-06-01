// @codebase
package io.nxmatic.rk2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeResult;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeService;
import io.nxmatic.rk2lab.manifests.api.ManifestYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits a consolidated multi-document YAML synth into one file per resource under {@code
 * <layer>/<package>/<order>-<kind>-<name>.yml}.
 *
 * <p>Replaces the old {@code bin/explode-manifests.sh} that used {@code yq} — the synth itself runs
 * in seed-master at pulumi-up time now, and we don't want a {@code yq} runtime dep.
 *
 * <p>Layer and package come from {@code io.nxmatic.rke2lab/layer} and {@code
 * io.nxmatic.rke2lab/package} annotations stamped by layer code; defaults match the old script
 * ({@code default} / {@code unknown}). Order prefix is determined by kind: {@code 00-} for CRDs,
 * {@code 01-} for other cluster-scoped resources (no namespace), {@code 02-} for namespace-scoped
 * resources.
 */
public final class DefaultManifestExplodeService implements ManifestExplodeService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestExplodeService.class);

  private static final String CRD_KIND = "CustomResourceDefinition";

  @Override
  public String providerId() {
    return "default-jackson-exploder";
  }

  @Override
  public ManifestExplodeResult explode(ManifestExplodeRequest request) throws IOException {
    final Path source = request.consolidatedManifestFile();
    final Path target = request.explodedTargetDir();

    if (!Files.isRegularFile(source)) {
      throw new IllegalStateException("Consolidated manifest file not found: " + source);
    }

    // Note: we do NOT wipe target here. The caller may have other content under
    // it (e.g. host/ assets in seed-master's manifestsRoot) that must survive.
    // Callers are responsible for clearing stale per-resource files before
    // invoking explode.
    Files.createDirectories(target);

    final List<Path> written = new ArrayList<>();

    try (MappingIterator<JsonNode> documents = ManifestYaml.readNodes(source)) {
      while (documents.hasNext()) {
        final JsonNode document = documents.next();
        if (document == null || !document.isObject()) {
          continue;
        }

        final String layer = annotation(document, "io.nxmatic.rke2lab/layer", "default");
        final String pkg = annotation(document, "io.nxmatic.rke2lab/package", "unknown");
        final String kind = textOrNull(document.get("kind"));
        if (kind == null) {
          continue;
        }
        final String namespace = textOrNull(document.path("metadata").get("namespace"));
        final String name = sanitizeFileSegment(textOrNull(document.path("metadata").get("name")));
        final String order = orderPrefixFor(kind, namespace);
        final String fileName = order + "-" + kind.toLowerCase(Locale.ROOT) + "-" + name + ".yml";

        final Path outFile = target.resolve(layer).resolve(pkg).resolve(fileName);
        ManifestYaml.writeDocument(outFile, document);
        written.add(outFile);
      }
    }

    written.sort(Comparator.naturalOrder());

    LOG.info("Exploded {} resources from {} into {}", written.size(), source.getFileName(), target);

    return new ManifestExplodeResult(target, written);
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

  private static String annotation(JsonNode document, String key, String fallback) {
    final JsonNode value = document.path("metadata").path("annotations").get(key);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private static String sanitizeFileSegment(String value) {
    if (value == null || value.isBlank()) {
      return "unnamed";
    }
    return value.toLowerCase(Locale.ROOT).replace(':', '-').replace('/', '-');
  }
}
