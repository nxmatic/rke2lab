// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.upstream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import org.yaml.snakeyaml.LoaderOptions;
import software.constructs.Construct;

/**
 * Wraps an upstream multi-document YAML release artifact (shipped as a classpath resource) into
 * cdk8s {@link ApiObject} instances stamped with the project's {@code kpt.*} annotations so the
 * manifest exploder writes them under the layer's package directory.
 *
 * <p>This is the canonical way to bring an upstream operator's release manifest (e.g. {@code
 * https://infra.tekton.dev/.../release.yaml}) into the synth pipeline without translating each
 * resource by hand. The classpath YAML stays as a frozen artifact in the repo, version-pinned by
 * filename; upgrades are a drop-in replacement plus a Java string bump.
 *
 * <p>Subclass to specialize behavior — override {@link #accept} to filter documents (e.g. drop a
 * Namespace because the project ships its own), {@link #transform} to mutate documents (e.g. pin an
 * image tag), or {@link #upstreamIdentifierFor} to change how the {@code
 * internal.kpt.dev/upstream-identifier} annotation is derived.
 *
 * <p>Resources are constructed eagerly during instantiation. Use {@link #apiObjects()} to recover
 * the cdk8s constructs (e.g. to add cross-resource dependencies).
 */
public class UpstreamYamlInclusion {

  private final List<ApiObject> apiObjects;

  public UpstreamYamlInclusion(
      final Construct scope,
      final String classpathResource,
      final PackageMetadataProfile packageProfile) {
    this.apiObjects = build(scope, classpathResource, packageProfile);
  }

  /** All resources emitted from the included YAML, in document order. */
  public final List<ApiObject> apiObjects() {
    return Collections.unmodifiableList(apiObjects);
  }

  /**
   * Filter hook. Default keeps every document. Override to skip resources the project ships
   * separately (e.g. a namespace whose policy/labels diverge from upstream).
   */
  protected boolean accept(final Map<String, Object> document) {
    return true;
  }

  /**
   * Transform hook. Default returns the document unchanged. Override to pin image tags, inject
   * priority classes, override resource limits, etc.
   */
  protected Map<String, Object> transform(final Map<String, Object> document) {
    return document;
  }

  /**
   * Builds the {@code internal.kpt.dev/upstream-identifier} annotation value. Default format is
   * {@code <apiGroup>|<kind>|<namespace>|<name>} — matches how project-authored layers stamp the
   * annotation (see e.g. {@code TailscaleLayer}). Override only if a specific upstream wants a
   * different shape.
   */
  protected String upstreamIdentifierFor(
      final String apiGroup, final String kind, final String namespace, final String name) {
    return apiGroup + "|" + kind + "|" + (namespace == null ? "" : namespace) + "|" + name;
  }

  @SuppressWarnings("unchecked")
  private List<ApiObject> build(
      final Construct scope,
      final String classpathResource,
      final PackageMetadataProfile packageProfile) {
    final List<Map<String, Object>> documents = readDocuments(classpathResource);
    final List<ApiObject> emitted = new ArrayList<>();

    int index = 0;
    for (Map<String, Object> document : documents) {
      if (!accept(document)) {
        continue;
      }
      final Map<String, Object> shaped = transform(document);

      final String apiVersion = stringField(shaped, "apiVersion");
      final String kind = stringField(shaped, "kind");
      if (apiVersion == null || kind == null) {
        continue;
      }
      final Map<String, Object> metadata = nestedMap(shaped, "metadata");
      final String name = metadata == null ? null : (String) metadata.get("name");
      final String namespace = metadata == null ? null : (String) metadata.get("namespace");
      if (name == null || name.isBlank()) {
        continue;
      }

      final String apiGroup = apiGroupOf(apiVersion);
      final String upstreamIdentifier = upstreamIdentifierFor(apiGroup, kind, namespace, name);

      final Map<String, String> annotations =
          mergeStringMap(
              packageProfile.packageAnnotations(upstreamIdentifier),
              metadata == null ? null : (Map<String, String>) metadata.get("annotations"));
      final Map<String, String> labels =
          metadata == null ? null : (Map<String, String>) metadata.get("labels");

      final ApiObjectMetadata.Builder metaBuilder =
          ApiObjectMetadata.builder().name(name).annotations(annotations);
      if (namespace != null && !namespace.isBlank()) {
        metaBuilder.namespace(namespace);
      }
      if (labels != null && !labels.isEmpty()) {
        metaBuilder.labels(labels);
      }

      final ApiObject apiObject =
          new ApiObject(
              scope,
              constructIdFor(classpathResource, kind, name, namespace, index),
              ApiObjectProps.builder()
                  .apiVersion(apiVersion)
                  .kind(kind)
                  .metadata(metaBuilder.build())
                  .build());

      // Anything outside metadata/apiVersion/kind goes through a JSON-patch so cdk8s does not try
      // to map it onto its strongly-typed metadata builder. This includes spec, data, rules, etc.
      for (Map.Entry<String, Object> entry : shaped.entrySet()) {
        final String key = entry.getKey();
        if ("apiVersion".equals(key) || "kind".equals(key) || "metadata".equals(key)) {
          continue;
        }
        apiObject.addJsonPatch(JsonPatch.add("/" + key, entry.getValue()));
      }

      emitted.add(apiObject);
      index++;
    }

    return emitted;
  }

  private static String constructIdFor(
      final String classpathResource,
      final String kind,
      final String name,
      final String namespace,
      final int index) {
    final String stem = classpathResource.replaceAll(".*/", "").replaceAll("\\..*$", "");
    final String nsPart = namespace == null || namespace.isBlank() ? "" : "-" + namespace;
    return "upstream-" + stem + "-" + index + "-" + kind.toLowerCase() + nsPart + "-" + name;
  }

  private static String apiGroupOf(final String apiVersion) {
    final int slash = apiVersion.indexOf('/');
    return slash < 0 ? "" : apiVersion.substring(0, slash);
  }

  private static String stringField(final Map<String, Object> document, final String key) {
    final Object value = document.get(key);
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedMap(
      final Map<String, Object> document, final String key) {
    final Object value = document.get(key);
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
  }

  private static Map<String, String> mergeStringMap(
      final Map<String, String> base, final Map<String, String> overlay) {
    if (overlay == null || overlay.isEmpty()) {
      return base;
    }
    final LinkedHashMap<String, String> merged = new LinkedHashMap<>(base);
    merged.putAll(overlay);
    return Map.copyOf(merged);
  }

  private static List<Map<String, Object>> readDocuments(final String classpathResource) {
    final String resourcePath =
        classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
    try (InputStream in =
        UpstreamYamlInclusion.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalArgumentException("Classpath resource not found: " + classpathResource);
      }
      try (MappingIterator<Map<String, Object>> iterator =
          YAML_MAPPER.readerFor(MAP_TYPE).readValues(in)) {
        final List<Map<String, Object>> documents = new ArrayList<>();
        while (iterator.hasNext()) {
          final Map<String, Object> document = iterator.next();
          if (document != null) {
            documents.add(document);
          }
        }
        return documents;
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read classpath resource: " + classpathResource, ex);
    }
  }

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Tekton's release.yaml carries large CRD schemas; lift SnakeYaml's per-document code-point cap
   * (Jackson YAML wraps SnakeYaml internally) from the default 3 MiB to 64 MiB to be safe against
   * future bundles.
   */
  private static final ObjectMapper YAML_MAPPER = buildMapper();

  private static ObjectMapper buildMapper() {
    final LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setCodePointLimit(64 * 1024 * 1024);
    final ObjectMapper mapper =
        new ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build());
    // Multi-doc YAML often contains stray `---` separators that yield an empty document; coerce
    // those to null so the iterator filter can skip them instead of throwing.
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
    return mapper;
  }
}
