package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.osgi.service.component.annotations.Component;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * The deterministic YAML (de)serialization service for the manifest pipeline — a single OSGi
 * {@code @Component} every YAML-emitting path goes through, so the rendering guarantees live in one
 * place. DS consumers reach it by {@code @Reference}; the CDK8s units (not components) reach it
 * through the {@link ManifestsUnitContext} the synthesizer hands each unit. A plain {@code new
 * YamlMapper()} is also valid (tests, ad-hoc use) — every instance shares the same configured
 * mapper, so "single source of truth" is single CONFIGURATION, guaranteed by one {@link
 * #buildMapper()}.
 *
 * <p>The configured mapper guarantees:
 *
 * <ul>
 *   <li>Map keys sorted alphabetically ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}) so
 *       output is byte-deterministic regardless of how callers built their maps — this killed a
 *       recurring bug class where {@code Map.of(...)} hash randomization churned config checksums.
 *   <li>Multi-line strings render as YAML literal block scalars ({@link
 *       YAMLGenerator.Feature#LITERAL_BLOCK_STYLE}), no custom representers or regex
 *       post-processing.
 *   <li>A 64 MiB code-point limit on the SnakeYAML parser so the flox installer-assets ConfigMap
 *       (~tens of MiB of base64) round-trips without hitting the default 3 MiB cap.
 *   <li>Empty documents (stray {@code ---} separators in upstream multi-doc YAML like Tekton's
 *       release.yaml) coerce to {@code null} so the stream can skip them.
 *   <li>Numeric-looking strings (e.g. an {@code EnvVar.value} of {@code "6443"}) stay quoted via
 *       {@link YAMLGenerator.Feature#ALWAYS_QUOTE_NUMBERS_AS_STRINGS}, so the Kubernetes API server
 *       does not reject string-typed values emitted as bare YAML numbers.
 * </ul>
 *
 * <p>Do not introduce another {@code ObjectMapper} or a {@code StringBuilder yaml.append("---\n")}
 * writer — extend this service instead, so the guarantees above stay in one place. I/O failures
 * surface as {@link UncheckedIOException} (the repo convention), keeping the read/write API usable
 * inside fluent lambda chains.
 */
@Component(service = YamlMapper.class)
public final class YamlMapper {

  private final ObjectMapper mapper = buildMapper();

  /** Render a single document to a YAML string (with leading {@code ---} document marker). */
  public String dump(Object document) {
    try {
      return mapper.writeValueAsString(document);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to render YAML document", ex);
    }
  }

  /** Read a single document of {@code type} from {@code source}. */
  public <T> T read(Path source, Class<T> type) {
    try {
      return mapper.readValue(source.toFile(), type);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read YAML from " + source, ex);
    }
  }

  /** Begin a fluent write to {@code target}; parent directories are created on write. */
  public Write write(Path target) {
    return new Write(target);
  }

  /** Begin a fluent read from a file {@code source}. */
  public Read read(Path source) {
    return new Read(reader -> reader.readValues(source.toFile()), source);
  }

  /** Begin a fluent read from an arbitrary stream (e.g. a classpath resource). */
  public Read read(InputStream source) {
    return new Read(reader -> reader.readValues(source), source);
  }

  /** Fluent writer over {@code target}. */
  public final class Write {
    private final Path target;

    private Write(Path target) {
      this.target = target;
    }

    /** Write a single document, creating parent directories if needed. */
    public void document(Object document) {
      writeString(dump(document));
    }

    /** Write a sequence of documents as one multi-doc YAML stream, each with its {@code ---}. */
    public void documents(Iterable<?> documents) {
      final StringWriter buffer = new StringWriter();
      try (SequenceWriter sequence = mapper.writer().writeValues(buffer)) {
        for (Object document : documents) {
          sequence.write(document);
        }
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed to render YAML documents for " + target, ex);
      }
      writeString(buffer.toString());
    }

    private void writeString(String content) {
      try {
        if (target.getParent() != null) {
          Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed to write YAML to " + target, ex);
      }
    }
  }

  /** Fluent multi-doc reader; empty documents are filtered automatically. */
  public final class Read {
    private final ValuesSource source;
    private final Object origin;

    private Read(ValuesSource source, Object origin) {
      this.source = source;
      this.origin = origin;
    }

    /** Stream the documents as {@link JsonNode} trees. */
    public Stream<JsonNode> nodes() {
      return stream(mapper.readerFor(JsonNode.class));
    }

    /** Stream the documents as {@code type}, skipping the empty ones. */
    public <T> Stream<T> as(Class<T> type) {
      return stream(mapper.readerFor(type));
    }

    /** Stream the documents as a parameterized {@code type} (e.g. {@code Map<String,Object>}). */
    public <T> Stream<T> as(TypeReference<T> type) {
      return stream(mapper.readerFor(type));
    }

    @SuppressWarnings("unchecked")
    private <T> Stream<T> stream(ObjectReader reader) {
      final MappingIterator<T> iterator;
      try {
        iterator = (MappingIterator<T>) source.open(reader);
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed to read YAML from " + origin, ex);
      }
      return StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
          .filter(document -> document != null);
    }
  }

  /** Opens a {@link MappingIterator} over this read's origin, using the type-bound reader. */
  @FunctionalInterface
  private interface ValuesSource {
    MappingIterator<?> open(ObjectReader reader) throws IOException;
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
    // those to null so the stream filter can skip them instead of throwing.
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);

    return mapper;
  }
}
