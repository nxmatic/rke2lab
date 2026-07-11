// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.contract.ManifestAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Base class for manifest units following the lifecycle model (see
 * docs/manifests-unit-lifecycle.adoc): a plain object holding metadata, with synthesis happening in
 * {@link #apply(ManifestsUnitContext)}.
 *
 * <p>The base implements a <b>template method</b>: {@code apply} creates a CDK8s scope Construct
 * under the chart scope, calls the subclass {@link #doSynthesize(Construct, ManifestsUnitContext)}
 * hook, introspects the resulting children, and emits a group marker ConfigMap that inventories
 * them. The marker is a hidden dotfile ({@code .configmap-<package>.group.yml}) carrying {@code
 * config.kubernetes.io/local-config: "true"}, so it creates the output directory deterministically
 * but is never linked by the installer.
 *
 * <p><b>Subclass contract</b>: the constructor is pure initialization (assign metadata fields); all
 * domain logic (creating ApiObjects) belongs in {@link #doSynthesize}. The scope passed to that
 * hook is the scope for {@code new ApiObject(scope, ...)}.
 */
public abstract class AbstractManifestsUnit implements ManifestsUnit {

  private final String manifestUnitId;
  private final List<String> dependsOnManifestsUnitIds;

  protected AbstractManifestsUnit(
      final String manifestUnitId, final List<String> dependsOnManifestsUnitIds) {
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestsUnitIds = List.copyOf(dependsOnManifestsUnitIds);
  }

  @Override
  public final String manifestUnitId() {
    return manifestUnitId;
  }

  @Override
  public final List<String> dependsOnManifestsUnitIds() {
    return dependsOnManifestsUnitIds;
  }

  @Override
  public final void apply(final ManifestsUnitContext context) {
    // Template method: create scope, call subclass hook, introspect, emit group marker
    final Construct scope = new Construct(context.chart(), manifestUnitId.replace("/", "-"));

    doSynthesize(scope, context);

    emitGroupMarker(scope, context);
  }

  /**
   * Subclass hook where domain logic lives. Create ApiObjects in the given scope, read context for
   * runtime config (component versions, bootstrap identity, network topology, etc.). The scope is a
   * fresh Construct parented under the chart; it groups this unit's manifests under one CDK8s node.
   *
   * <p>The base calls this after creating the scope and before emitting the group marker.
   *
   * @param scope the CDK8s Construct scope for this unit's ApiObjects (replaces {@code this} in the
   *     old model)
   * @param context full synthesis context (chart, domain, unit id, reference registry)
   */
  protected abstract void doSynthesize(Construct scope, ManifestsUnitContext context);

  private void emitGroupMarker(final Construct scope, final ManifestsUnitContext context) {
    // Introspect the scope's children (what the subclass emitted)
    final List<ApiObject> children =
        scope.getNode().getChildren().stream()
            .filter(c -> c instanceof ApiObject)
            .map(c -> (ApiObject) c)
            .toList();

    // Build inventory: apiVersion|kind|namespace|name per child
    final String inventory =
        children.stream()
            .map(
                obj -> {
                  final String ns = obj.getMetadata().getNamespace();
                  return obj.getApiVersion()
                      + "|"
                      + obj.getKind()
                      + "|"
                      + (ns != null ? ns : "")
                      + "|"
                      + obj.getName();
                })
            .collect(Collectors.joining("\n"));

    // Emit the marker: a ConfigMap carrying local-config annotation + the inventory
    final String markerName = outputDir() + ".group";
    ApiObject marker =
        new ApiObject(
            scope,
            "group-marker",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(markerName)
                        .annotations(groupMarkerAnnotations(context.domainId()))
                        .build())
                .build());

    marker.addJsonPatch(JsonPatch.add("/data", Map.of("members", inventory)));
  }

  private Map<String, String> groupMarkerAnnotations(final String domainId) {
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotations.LOCAL_CONFIG, "true");
    annotations.put(ManifestAnnotations.MANIFEST_GROUP, "true");
    annotations.put(ManifestAnnotations.DOMAIN, domainId);
    annotations.put(ManifestAnnotations.PACKAGE, outputDir());
    return Map.copyOf(annotations);
  }
}
