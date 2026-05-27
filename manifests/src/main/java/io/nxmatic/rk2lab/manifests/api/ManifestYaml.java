package io.nxmatic.rk2lab.manifests.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Single source of truth for YAML rendering across the manifest pipeline.
 *
 * <p>Every YAML-emitting code path (CDK8s synth round-trip, manifest explode, hand-built ConfigMap
 * overlays, ad-hoc env-section writers) MUST go through this class. It guarantees:
 *
 * <ul>
 *   <li>Map keys are sorted alphabetically ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS})
 *       so output is byte-deterministic regardless of how callers built their input maps. This
 *       eliminates a recurring bug class where {@code Map.of(...)} hash randomization caused
 *       checksum churn in instance config.
 *   <li>Multi-line strings render as YAML literal block scalars ({@link
 *       YAMLGenerator.Feature#LITERAL_BLOCK_STYLE}), removing the need for custom representers or
 *       regex post-processing for ConfigMap script bodies.
 *   <li>The 64 MiB code-point limit is set on the underlying SnakeYAML parser so the flox
 *       installer-assets ConfigMap (~tens of MiB of base64) can round-trip without hitting the
 *       default 3 MiB cap.
 *   <li>Empty documents (stray {@code ---} separators in upstream multi-doc YAML like Tekton's
 *       release.yaml) coerce to {@code null} so the iterator filter can skip them without a {@code
 *       Cannot coerce empty String} exception.
 *   <li>Numeric-looking strings (e.g. an {@code EnvVar.value} of {@code "6443"}) stay quoted via
 *       {@link YAMLGenerator.Feature#ALWAYS_QUOTE_NUMBERS_AS_STRINGS}. Without it, {@link
 *       YAMLGenerator.Feature#MINIMIZE_QUOTES} would emit them as bare YAML numbers and the
 *       Kubernetes API server would reject the manifest (env values are typed {@code string}).
 * </ul>
 *
 * <p>Do not introduce another {@code ObjectMapper} or {@code StringBuilder yaml.append("---\n")}
 * writer — extend this class instead, so the guarantees above stay in one place.
 */
public final class ManifestYaml {

  private static final ObjectMapper MAPPER = buildMapper();

  private ManifestYaml() {
    // Static utility.
  }

  /** Render a single document to a YAML string (with leading {@code ---} document marker). */
  public static String dump(Object document) {
    try {
      return MAPPER.writeValueAsString(document);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to render YAML document", ex);
    }
  }

  /** Write a single document to {@code target}, creating parent directories if needed. */
  public static void writeDocument(Path target, Object document) throws IOException {
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    Files.writeString(target, dump(document), StandardCharsets.UTF_8);
  }

  /**
   * Write a sequence of documents to {@code target} as a single multi-doc YAML stream. Each
   * document gets its own {@code ---} marker.
   */
  public static void writeDocuments(Path target, Iterable<?> documents) throws IOException {
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    final StringWriter buffer = new StringWriter();
    try (SequenceWriter sequence = MAPPER.writer().writeValues(buffer)) {
      for (Object document : documents) {
        sequence.write(document);
      }
    }
    Files.writeString(target, buffer.toString(), StandardCharsets.UTF_8);
  }

  /**
   * Stream a multi-doc YAML file as {@link JsonNode} documents. Empty documents are filtered
   * automatically (see {@link CoercionInputShape#EmptyString} configuration).
   */
  public static MappingIterator<JsonNode> readNodes(Path source) throws IOException {
    return MAPPER.readerFor(JsonNode.class).readValues(source.toFile());
  }

  /**
   * Stream a multi-doc YAML file as the requested type. Empty documents coerce to {@code null} so
   * callers can filter them.
   */
  public static <T> MappingIterator<T> readValues(Path source, Class<T> type) throws IOException {
    return MAPPER.readerFor(type).readValues(source.toFile());
  }

  /**
   * Variant of {@link #readValues(Path, Class)} that accepts a {@link TypeReference} for parameter-
   * ized types like {@code Map<String, Object>}.
   */
  public static <T> MappingIterator<T> readValues(Path source, TypeReference<T> type)
      throws IOException {
    return MAPPER.readerFor(type).readValues(source.toFile());
  }

  /** Stream a multi-doc YAML stream from an arbitrary input source (e.g. classpath resource). */
  public static <T> MappingIterator<T> readValues(InputStream source, TypeReference<T> type)
      throws IOException {
    return MAPPER.readerFor(type).readValues(source);
  }

  /** Access the underlying mapper for callers that need it (e.g. tree-mode reads). */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  private static ObjectMapper buildMapper() {
    // SnakeYAML (which Jackson YAML wraps) caps single-document parses at 3 MiB by default. The
    // synthesized manifest carries the base64-encoded NRI plugin archive plus the flox
    // installer-assets ConfigMap, so single ConfigMap documents can sit well above that.
    final LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setCodePointLimit(64 * 1024 * 1024);

    final YAMLFactory factory =
        YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .enable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build();

    final ObjectMapper mapper =
        new ObjectMapper(factory).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    // Multi-doc YAML often contains stray `---` separators that yield an empty document; coerce
    // those to null so the iterator filter can skip them instead of throwing.
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);

    return mapper;
  }
}
