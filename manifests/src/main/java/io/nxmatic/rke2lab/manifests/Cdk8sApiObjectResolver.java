package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.refs.ApiObjectRef;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.Chart;

/**
 * Resolves realized ApiObjects from the cdk8s construct tree by their Kubernetes coordinates (kind
 * + namespace + name), carried by the {@link ApiObjectRef}.
 *
 * <p>The cdk8s tree is the single source of truth: the producing unit creates the ApiObject and the
 * consuming unit resolves it here. Dependency ordering guarantees the producer ran first, so the
 * object is always present when required.
 *
 * <p>Results are cached per synthesis run to avoid repeated O(n) tree traversals.
 */
public final class Cdk8sApiObjectResolver {

  private final Chart chart;
  private final Map<String, ApiObject> cache = new LinkedHashMap<>();

  public Cdk8sApiObjectResolver(final Chart chart) {
    if (chart == null) {
      throw new IllegalArgumentException("chart must not be null");
    }
    this.chart = chart;
  }

  /**
   * Finds the ApiObject matching the ref's Kubernetes coordinates.
   *
   * @throws IllegalStateException if no matching ApiObject exists in the tree
   */
  public ApiObject require(final ApiObjectRef ref) {
    if (ref == null) {
      throw new IllegalArgumentException("ref must not be null");
    }

    final ApiObject cached = cache.get(ref.referenceId());
    if (cached != null) {
      return cached;
    }

    final ApiObject found =
        chart.getNode().findAll().stream()
            .filter(ApiObject.class::isInstance)
            .map(ApiObject.class::cast)
            .filter(
                obj ->
                    ref.kind().equals(obj.getKind())
                        && ref.name().equals(obj.getName())
                        && (ref.namespace() == null
                            || ref.namespace().equals(extractNamespace(obj))))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No ApiObject found in cdk8s tree for "
                            + ref.referenceId()
                            + " (kind="
                            + ref.kind()
                            + ", namespace="
                            + ref.namespace()
                            + ", name="
                            + ref.name()
                            + ")"));

    cache.put(ref.referenceId(), found);
    return found;
  }

  private String extractNamespace(final ApiObject obj) {
    final Object metadataObj = obj.getMetadata().toJson();
    if (!(metadataObj instanceof Map<?, ?> metadata)) {
      return null;
    }
    final Object namespaceObj = metadata.get("namespace");
    return namespaceObj instanceof String s ? s : null;
  }
}
