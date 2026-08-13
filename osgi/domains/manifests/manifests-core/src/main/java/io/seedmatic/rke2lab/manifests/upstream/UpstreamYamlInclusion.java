// @codebase
package io.seedmatic.rke2lab.manifests.upstream;

import com.fasterxml.jackson.core.type.TypeReference;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Wraps an upstream multi-document YAML release artifact (shipped as a classpath resource) into
 * cdk8s {@link ApiObject} instances stamped with the project's {@code io.seedmatic.rke2lab/*}
 * annotations so the manifest exploder writes them under the layer's package directory.
 *
 * <p>This is the canonical way to bring an upstream operator's release manifest (e.g. {@code
 * https://infra.tekton.dev/.../release.yaml}) into the synth pipeline without translating each
 * resource by hand. The classpath YAML stays as a frozen artifact in the repo, version-pinned by
 * filename; upgrades are a drop-in replacement plus a Java string bump.
 *
 * <p>Resources are constructed eagerly during instantiation. Use {@link #apiObjects()} to recover
 * the cdk8s constructs (e.g. to add cross-resource dependencies).
 */
public final class UpstreamYamlInclusion {

  private final List<ApiObject> apiObjects;

  public UpstreamYamlInclusion(
      final Construct scope,
      final String classpathResource,
      final PackageMetadataProfile packageProfile,
      final YamlMapper yaml) {
    this.apiObjects = build(scope, classpathResource, packageProfile, yaml);
  }

  /** All resources emitted from the included YAML, in document order. */
  public List<ApiObject> apiObjects() {
    return Collections.unmodifiableList(apiObjects);
  }

  private static boolean accept(final Map<String, Object> document) {
    return true;
  }

  private static Map<String, Object> transform(final Map<String, Object> document) {
    return document;
  }

  private static String upstreamIdentifierFor(
      final String apiGroup,
      final String kind,
      final Optional<String> namespace,
      final String name) {
    return apiGroup + "|" + kind + "|" + namespace.orElse("") + "|" + name;
  }

  @SuppressWarnings("unchecked")
  private static List<ApiObject> build(
      final Construct scope,
      final String classpathResource,
      final PackageMetadataProfile packageProfile,
      final YamlMapper yaml) {
    final List<Map<String, Object>> documents = readDocuments(classpathResource, yaml);
    final List<ApiObject> emitted = new ArrayList<>();

    int index = 0;
    for (Map<String, Object> document : documents) {
      if (!accept(document)) {
        continue;
      }
      final Map<String, Object> shaped = transform(document);

      final Optional<String> apiVersion = stringField(shaped, "apiVersion");
      final Optional<String> kind = stringField(shaped, "kind");
      if (apiVersion.isEmpty() || kind.isEmpty()) {
        continue;
      }
      final Optional<Map<String, Object>> metadata = nestedMap(shaped, "metadata");
      final Optional<String> name =
          metadata.map(m -> (String) m.get("name")).filter(n -> !n.isBlank());
      if (name.isEmpty()) {
        continue;
      }
      final Optional<String> namespace =
          metadata.map(m -> (String) m.get("namespace")).filter(ns -> !ns.isBlank());

      final String apiGroup = apiGroupOf(apiVersion.get());
      final String upstreamIdentifier =
          upstreamIdentifierFor(apiGroup, kind.get(), namespace, name.get());

      final Map<String, String> annotations =
          mergeStringMap(
              packageProfile.packageAnnotations(upstreamIdentifier),
              metadata.map(m -> (Map<String, String>) m.get("annotations")));
      final Optional<Map<String, String>> labels =
          metadata.map(m -> (Map<String, String>) m.get("labels")).filter(l -> !l.isEmpty());

      final ApiObjectMetadata.Builder metaBuilder =
          ApiObjectMetadata.builder().name(name.get()).annotations(annotations);
      namespace.ifPresent(metaBuilder::namespace);
      labels.ifPresent(metaBuilder::labels);

      final ApiObject apiObject =
          new ApiObject(
              scope,
              constructIdFor(classpathResource, kind.get(), name.get(), namespace, index),
              ApiObjectProps.builder()
                  .apiVersion(apiVersion.get())
                  .kind(kind.get())
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
      final Optional<String> namespace,
      final int index) {
    final String stem = classpathResource.replaceAll(".*/", "").replaceAll("\\..*$", "");
    final String nsPart = namespace.map(ns -> "-" + ns).orElse("");
    return "upstream-" + stem + "-" + index + "-" + kind.toLowerCase() + nsPart + "-" + name;
  }

  private static String apiGroupOf(final String apiVersion) {
    final int slash = apiVersion.indexOf('/');
    return slash < 0 ? "" : apiVersion.substring(0, slash);
  }

  private static Optional<String> stringField(
      final Map<String, Object> document, final String key) {
    return Optional.ofNullable(document.get(key)).map(Object::toString);
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> nestedMap(
      final Map<String, Object> document, final String key) {
    final Object value = document.get(key);
    return value instanceof Map<?, ?> ? Optional.of((Map<String, Object>) value) : Optional.empty();
  }

  private static Map<String, String> mergeStringMap(
      final Map<String, String> base, final Optional<Map<String, String>> overlay) {
    return overlay
        .filter(o -> !o.isEmpty())
        .map(
            o -> {
              final LinkedHashMap<String, String> merged = new LinkedHashMap<>(base);
              merged.putAll(o);
              return Map.copyOf(merged);
            })
        .orElse(base);
  }

  private static List<Map<String, Object>> readDocuments(
      final String classpathResource, final YamlMapper yaml) {
    final String resourcePath =
        classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
    try (InputStream in =
        UpstreamYamlInclusion.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalArgumentException("Classpath resource not found: " + classpathResource);
      }
      return yaml.read(in).as(MAP_TYPE).toList();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read classpath resource: " + classpathResource, ex);
    }
  }

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
}
