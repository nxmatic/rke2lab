// @codebase
package io.nxmatic.rk2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeResult;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;

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

  /**
   * Jackson YAML mapper configured for deterministic output: {@link
   * SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} sorts every map alphabetically, eliminating
   * checksum churn from {@code Map.of()} hash randomization upstream in CDK8s.
   */
  private static final ObjectMapper YAML_MAPPER = buildMapper();

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
    // it (e.g. host/ assets in seed-bootstrap's manifestsRoot) that must survive.
    // Callers are responsible for clearing stale per-resource files before
    // invoking explode.
    Files.createDirectories(target);

    final List<Path> written = new ArrayList<>();

    try (MappingIterator<JsonNode> documents =
        YAML_MAPPER.readerFor(JsonNode.class).readValues(source.toFile())) {
      while (documents.hasNext()) {
        final JsonNode document = documents.next();
        if (document == null || !document.isObject()) {
          continue;
        }

        final String layer = annotation(document, "kpt.dev/package-layer", "default");
        final String pkg = annotation(document, "kpt.dev/package-name", "unknown");
        final String kind = textOrNull(document.get("kind"));
        if (kind == null) {
          continue;
        }
        final String namespace = textOrNull(document.path("metadata").get("namespace"));
        final String name = sanitizeFileSegment(textOrNull(document.path("metadata").get("name")));
        final String order = orderPrefixFor(kind, namespace);
        final String fileName = order + "-" + kind.toLowerCase(Locale.ROOT) + "-" + name + ".yml";

        final Path packageDir = target.resolve(layer).resolve(pkg);
        Files.createDirectories(packageDir);
        final Path outFile = packageDir.resolve(fileName);

        Files.writeString(outFile, YAML_MAPPER.writeValueAsString(document));
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

  /**
   * SnakeYaml's default 3 MiB code-point limit is too tight for the consolidated synth output: the
   * flox installer-assets ConfigMap embeds pre-locked flake/manifest locks plus a base64 NRI plugin
   * archive. Bump to 64 MiB to match the synthesizer's reader.
   */
  private static ObjectMapper buildMapper() {
    final LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setCodePointLimit(64 * 1024 * 1024);

    final YAMLFactory factory =
        YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .enable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build();
    return new ObjectMapper(factory).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }
}
