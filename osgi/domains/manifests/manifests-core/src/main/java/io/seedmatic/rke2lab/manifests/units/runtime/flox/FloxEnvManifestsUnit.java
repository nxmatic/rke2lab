package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Emits the workload {@code FloxEnv} CRs the flox-controller realises on each node — the runtime
 * successor to the baked {@code environment.d} tree. First increment: {@code kdns} (others —
 * headscale/headplane/tailscale — follow the same shape).
 *
 * <p>Each env installs its workload package from the {@link FloxCatalogManifestsUnit} catalog via a
 * {@code floxcatalog:catalogue#<output>} ref (resolved same-namespace, both live in {@code
 * rke2lab-system}). The env's {@code folder} is the GC-root category the NRI plugin keys on ({@code
 * <base>/networking/kdns}) — a node-side path segment, not a k8s namespace.
 *
 * <p>Flavor switches the env NAME (and its {@code #output}) by the networking debug toggle ({@code
 * FloxDebugPolicy.networkingEnabled}), matching {@code KdnsManifestsUnit}'s {@code
 * resolveNetworkingEnvironment("networking/kdns", "networking/kdns-debug")} pod annotation so the
 * provisioned GC-root ({@code networking/<name>}) is exactly what the pod waits on: prod is {@code
 * kdns} (stripped {@code #kdns}); debug is {@code kdns-debug} ({@code #kdns-debug}, delve-wrapped)
 * plus an interactive toolchain. On the {@code workloads} layer (after the catalog on {@code
 * operators}).
 */
public final class FloxEnvManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox-envs";

  /** Exploded package dir (relative to the runtime domain). */
  public static final String OUTPUT_DIR = "flox-envs";

  private static final String SCHEMA_VERSION = "1.10.0";

  /**
   * GC-root category (node-side path segment), matches the pod's {@code flox.dev/environment.<c>}.
   */
  private static final String NETWORKING_FOLDER = "networking";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.RUNTIME, OUTPUT_DIR, false);

  public FloxEnvManifestsUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            FloxCatalogManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final boolean debug = ManifestSynthesisContext.current().floxDebugPolicy().networkingEnabled();
    createKdnsEnv(scope, context.resolver(), debug);
  }

  private void createKdnsEnv(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final boolean debug) {
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    // Name by flavor so the provisioned GC-root (networking/<name>) matches KdnsManifestsUnit's
    // resolveNetworkingEnvironment pod annotation: prod=kdns, debug=kdns-debug.
    final String name = debug ? "kdns-debug" : "kdns";
    final ApiObject env =
        new ApiObject(
            scope,
            "floxenv-" + name,
            ApiObjectProps.builder()
                .apiVersion("flox.seedmatic.io/v1alpha1")
                .kind("FloxEnv")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "flox.seedmatic.io|FloxEnv|" + namespace + "|" + name))
                        .build())
                .build());
    env.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));

    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("folder", NETWORKING_FOLDER);
    spec.put("consumption", "overlay");
    spec.put("manifest", kdnsManifest(debug));
    env.addJsonPatch(JsonPatch.add("/spec", spec));
  }

  /** The flox manifest (mirrors {@code manifest.toml}) for the kdns env, per flavor. */
  private Map<String, Object> kdnsManifest(final boolean debug) {
    final String flavor = debug ? "kdns-debug" : "kdns";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("kdns", Map.of("flake", "floxcatalog:catalogue#" + flavor));
    if (debug) {
      // Interactive debug shell alongside the delve-wrapped binary: attach a debugger / poke
      // around.
      install.put("go", Map.of("pkg-path", "go", "version", "^1.25"));
      install.put("delve", Map.of("pkg-path", "delve"));
      install.put("bash", Map.of("pkg-path", "bash", "outputs", "all"));
      install.put("coreutils", Map.of("pkg-path", "coreutils", "outputs", "all"));
      install.put("strace", Map.of("pkg-path", "strace"));
      install.put("curl", Map.of("pkg-path", "curl"));
    }
    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema-version", SCHEMA_VERSION);
    manifest.put("install", install);
    // These envs activate inside Linux containers on the nodes — restrict resolution to Linux.
    manifest.put("options", Map.of("systems", new Object[] {"aarch64-linux"}));
    return manifest;
  }
}
