package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Binds the flox-controller's nix-flake catalog to a Flux source. The workload flox packages (kdns,
 * headscale, …) are defined in the {@code runtime/flox} flake, published to a dedicated {@value
 * #CATALOGUE_BRANCH} branch (the flake at branch root, a {@code git subtree split} of the {@code
 * runtime/flox} resource tree — NOT the per-cluster rendered {@code manifests/<slug>} branch, which
 * carries only synthesised manifests). This unit emits:
 *
 * <ul>
 *   <li>a dedicated {@code GitRepository} tracking {@value #CATALOGUE_BRANCH} (same repo + GitHub
 *       App auth as the rendered-branch source), so Flux exposes it as an in-cluster tarball
 *       artifact at the exact reconciled commit — zero github fetch / token on the node side;
 *   <li>a {@code FloxFlake} "{@value #FLOX_FLAKE_NAME}" whose {@code sourceRef} points at that
 *       {@code GitRepository}. The controller reads the artifact and resolves {@code
 *       floxflake:rke2lab-system/catalogue#<output>} install refs to {@code
 *       tarball+http://…#<output>}.
 * </ul>
 *
 * <p>On the {@code operators} layer: the catalog source must be Ready before workload {@code
 * FloxEnv}s (on {@code workloads}) resolve their flake refs against it.
 */
public final class FloxFlakeCatalogueManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.RUNTIME + "/flox-flake-catalogue";

  /** Exploded package dir (relative to the runtime domain). */
  public static final String OUTPUT_DIR = "flox-flake-catalogue";

  /** Dedicated branch carrying the flake at its root (subtree split of {@code runtime/flox}). */
  public static final String CATALOGUE_BRANCH = "flox-catalogue";

  /** The single catalog FloxFlake, referenced as {@code floxflake:rke2lab-system/catalogue#…}. */
  public static final String FLOX_FLAKE_NAME = "catalogue";

  private static final String GIT_REPOSITORY_NAME = "flox-catalogue";
  private static final String REPO_URL = "https://github.com/seedmatic/rke2lab.git";
  private static final String APP_AUTH_SECRET = "githubapp";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.RUNTIME, OUTPUT_DIR, false, ManifestAnnotations.LAYER_OPERATORS);

  public FloxFlakeCatalogueManifestsUnit() {
    super(
        MANIFEST_UNIT_ID, java.util.List.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ApiObject gitRepository = createGitRepository(scope);
    createFloxFlake(scope, context.resolver(), gitRepository);
  }

  private ApiObject createGitRepository(final Construct scope) {
    final ApiObject gitRepo =
        new ApiObject(
            scope,
            "gitrepository-flox-catalogue",
            ApiObjectProps.builder()
                .apiVersion("source.toolkit.fluxcd.io/v1")
                .kind("GitRepository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(GIT_REPOSITORY_NAME)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "source.toolkit.fluxcd.io|GitRepository|flux-system|"
                                    + GIT_REPOSITORY_NAME))
                        .build())
                .build());
    gitRepo.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interval",
                "5m",
                "url",
                REPO_URL,
                "ref",
                Map.of("branch", CATALOGUE_BRANCH),
                // GitHub App auth REQUIRES provider=github (source-controller rejects App-data
                // secrets otherwise) — same lane as the rendered-branch GitRepository.
                "provider",
                "github",
                "secretRef",
                Map.of("name", APP_AUTH_SECRET))));
    return gitRepo;
  }

  private void createFloxFlake(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final ApiObject gitRepository) {
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    final ApiObject floxFlake =
        new ApiObject(
            scope,
            "floxflake-catalogue",
            ApiObjectProps.builder()
                .apiVersion("flox.seedmatic.io/v1alpha1")
                .kind("FloxFlake")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(FLOX_FLAKE_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "flox.seedmatic.io|FloxFlake|" + namespace + "|" + FLOX_FLAKE_NAME))
                        .build())
                .build());
    floxFlake.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    floxFlake.addDependency(gitRepository);
    // dir omitted: the flake sits at the branch root, so the controller derives
    // tarball+http://…/<sha>.tar.gz#<output> with no ?dir= segment.
    floxFlake.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "sourceRef",
                Map.of(
                    "kind", "GitRepository",
                    "name", GIT_REPOSITORY_NAME,
                    "namespace", "flux-system"))));
  }
}
