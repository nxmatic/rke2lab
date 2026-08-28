// @codebase
package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.MeshRefs;
import io.seedmatic.rke2lab.manifests.units.platform.ReplicatorManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.RuntimeRefs;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Owns the shared flox-runtime config for flox-consuming workloads: the {@code flox-env} ConfigMap
 * (metrics/telemetry off, non-interactive), replicated into the mesh namespace and consumed via
 * {@code envFrom} by Headscale/Headplane.
 *
 * <p>The flox NRI plugin is baked as a systemd service and the workload env GC-roots ride the node
 * image, so the former DaemonSet installer (and its {@code /srv/host} asset tree) was retired with
 * the NixOS node substrate; the flox environment provisioning it used to do is now the
 * flox-controller's ({@link FloxControllerManifestsUnit}).
 */
public final class FloxRuntimeManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox";

  /** Exploded package dir (relative to the runtime domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "flox-runtime";

  private static final String DOMAIN_NAME = ManifestDomainCatalog.RUNTIME;

  private static final String PACKAGE_NAME = OUTPUT_DIR;

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  public FloxRuntimeManifestsUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            ReplicatorManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createFloxEnvConfigMap(scope, context.resolver());
  }

  private ApiObject createFloxEnvConfigMap(
      final Construct scope, final Cdk8sApiObjectResolver resolver) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-" + RuntimeRefs.FLOX_ENV_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeRefs.FLOX_ENV_CONFIGMAP.name())
                        .namespace(RuntimeRefs.FLOX_ENV_CONFIGMAP.namespaceName())
                        .annotations(
                            manifestAnnotations.packageAnnotations(
                                DOMAIN_NAME,
                                PACKAGE_NAME,
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-to",
                                    MeshRefs.HEADSCALE_SYSTEM_NAMESPACE.name())))
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .build())
                .build());

    configMap.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "FLOX_DISABLE_METRICS",
                "true",
                "FLOX_NO_TELEMETRY",
                "1",
                "FLOX_NONINTERACTIVE",
                "1")));
    return configMap;
  }
}
