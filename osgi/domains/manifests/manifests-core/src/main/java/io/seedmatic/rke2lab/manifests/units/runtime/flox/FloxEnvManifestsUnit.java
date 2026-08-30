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
 * successor to the baked {@code environment.d} tree. Covers {@code kdns} (networking) and {@code
 * headscale}/{@code tailscale}/{@code headplane} (mesh).
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
    final var policy = ManifestSynthesisContext.current().floxDebugPolicy();
    final Cdk8sApiObjectResolver resolver = context.resolver();
    // Each env is named by its flavor so the provisioned GC-root (<folder>/<name>) matches the
    // consuming pod's resolve*Environment annotation: prod=<name>, debug=<name>-debug.
    final boolean net = policy.networkingEnabled();
    createEnv(
        scope, resolver, net ? "kdns-debug" : "kdns", FloxEnvFolder.NETWORKING, kdnsManifest(net));
    // Mesh: headscale control server + tailscale (the gateway Deployment + client DaemonSet share
    // the tailscale env). Headplane migrates in a later increment.
    final boolean mesh = policy.meshEnabled();
    createEnv(
        scope,
        resolver,
        mesh ? "headscale-debug" : "headscale",
        FloxEnvFolder.MESH,
        headscaleManifest(mesh));
    createEnv(
        scope,
        resolver,
        mesh ? "tailscale-debug" : "tailscale",
        FloxEnvFolder.MESH,
        tailscaleManifest(mesh));
    createEnv(
        scope,
        resolver,
        mesh ? "headplane-debug" : "headplane",
        FloxEnvFolder.MESH,
        headplaneManifest(mesh));
  }

  private void createEnv(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final String name,
      final FloxEnvFolder folder,
      final Map<String, Object> manifest) {
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
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
    spec.put("folder", folder.value());
    spec.put("consumption", "overlay");
    spec.put("manifest", manifest);
    env.addJsonPatch(JsonPatch.add("/spec", spec));
  }

  /** The flox manifest (mirrors {@code manifest.toml}) for the kdns env, per flavor. */
  private Map<String, Object> kdnsManifest(final boolean debug) {
    final String flavor = debug ? "kdns-debug" : "kdns";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("kdns", flakeRef(flavor));
    if (debug) {
      // Interactive debug shell alongside the delve-wrapped binary: attach a debugger / poke
      // around.
      install.put("go", Map.of("pkg-path", "go", "version", "^1.25"));
      install.put("delve", catalog("delve"));
      install.put("bash", catalogAll("bash"));
      install.put("coreutils", catalogAll("coreutils"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    }
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/headscale[-debug]/manifest.toml}. */
  private Map<String, Object> headscaleManifest(final boolean debug) {
    final String flavor = debug ? "headscale-debug" : "headscale";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    // The bootstrap/wait scripts drive the cluster via kubectl (both flavors carry it).
    install.put("kubectl", catalogAll("kubectl"));
    if (debug) {
      install.put("delve", catalog("delve"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    } else {
      // bootstrap.sh parses `headscale ... -o yaml` with yq — prod only (the bootstrap Job always
      // activates the prod headscale env, never the delve-wrapped debug build).
      install.put("yq-go", catalogAll("yq-go"));
    }
    install.put(flavor, flakeRef(flavor));
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/tailscale[-debug]/manifest.toml}. */
  private Map<String, Object> tailscaleManifest(final boolean debug) {
    final String flavor = debug ? "tailscale-debug" : "tailscale";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    if (debug) {
      install.put("delve", catalog("delve"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    }
    install.put(flavor, flakeRef(flavor));
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/headplane[-debug]/manifest.toml}. */
  private Map<String, Object> headplaneManifest(final boolean debug) {
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    // The agent-sync script drives the cluster via kubectl + parses config with yq (both flavors).
    install.put("kubectl", catalogAll("kubectl"));
    install.put("yq-go", catalog("yq-go"));
    if (debug) {
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
      install.put("headplane-debug", flakeRef("headplane-debug"));
    } else {
      install.put("headplane", flakeRef("headplane"));
      // headplane reads the headscale config/CLI for its integration — prod env carries it.
      install.put("headscale", flakeRef("headscale"));
    }
    // hp_agent (the tailnet agent) + the ssh WASM helper are separate flake outputs both flavors
    // need — headplane symlinks /usr/libexec/headplane/agent to `command -v hp_agent`.
    install.put("headplane-agent", flakeRef("headplane-agent"));
    install.put("headplane-ssh-wasm", flakeRef("headplane-ssh-wasm"));
    return manifest(install);
  }

  /**
   * A flake install resolved against the FloxCatalog artifact ({@code floxcatalog:catalogue#…}).
   */
  private Map<String, Object> flakeRef(final String output) {
    return Map.of("flake", "floxcatalog:catalogue#" + output);
  }

  /** A catalog install pulling a package's default output. */
  private Map<String, Object> catalog(final String pkg) {
    return Map.of("pkg-path", pkg);
  }

  /** A catalog install pulling all of a package's outputs (bin split across {@code out}/…). */
  private Map<String, Object> catalogAll(final String pkg) {
    return Map.of("pkg-path", pkg, "outputs", "all");
  }

  private Map<String, Object> manifest(final Map<String, Object> install) {
    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema-version", SCHEMA_VERSION);
    manifest.put("install", install);
    // These envs activate inside Linux containers on the nodes — restrict resolution to Linux.
    manifest.put("options", Map.of("systems", new Object[] {"aarch64-linux"}));
    return manifest;
  }
}
