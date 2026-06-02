package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.profiles.BootstrapIdentity;
import io.nxmatic.rk2lab.manifests.profiles.ComponentVersions;
import io.nxmatic.rk2lab.manifests.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.profiles.ImageState;
import io.nxmatic.rk2lab.manifests.profiles.NetworkTopology;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

/** Request contract for canonical manifest synthesis. */
public record ManifestSynthesisRequest(
    Path synthOutdir,
    Path synthManifestFile,
    Optional<ManifestDomainPolicy> manifestDomainPolicy,
    FloxDebugPolicy floxDebugPolicy,
    BootstrapIdentity bootstrapIdentity,
    NetworkTopology networkTopology,
    ComponentVersions componentVersions,
    ImageState imageState)
    implements ManifestDomainPolicyAware {

  private static final String ENABLED_DOMAINS_PROPERTY = "rk2lab.manifests.policy.enabledDomains";

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public ManifestSynthesisRequest(Path synthOutdir, Path synthManifestFile) {
    this(synthOutdir, synthManifestFile, Optional.empty(), FloxDebugPolicy.disabled());
  }

  public ManifestSynthesisRequest(
      Path synthOutdir,
      Path synthManifestFile,
      Optional<ManifestDomainPolicy> manifestDomainPolicy) {
    this(synthOutdir, synthManifestFile, manifestDomainPolicy, FloxDebugPolicy.disabled());
  }

  public ManifestSynthesisRequest(
      Path synthOutdir,
      Path synthManifestFile,
      Optional<ManifestDomainPolicy> manifestDomainPolicy,
      FloxDebugPolicy floxDebugPolicy) {
    this(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        floxDebugPolicy,
        BootstrapIdentity.unknown(),
        NetworkTopology.empty(),
        ComponentVersions.empty(),
        ImageState.unknown());
  }

  public ManifestSynthesisRequest {
    synthOutdir = synthOutdir.toAbsolutePath().normalize();
    synthManifestFile = synthManifestFile.toAbsolutePath().normalize();
    manifestDomainPolicy =
        manifestDomainPolicy == null
            ? Optional.empty()
            : manifestDomainPolicy.map(policy -> policy);
    floxDebugPolicy = floxDebugPolicy == null ? FloxDebugPolicy.disabled() : floxDebugPolicy;
    bootstrapIdentity = bootstrapIdentity == null ? BootstrapIdentity.unknown() : bootstrapIdentity;
    networkTopology = networkTopology == null ? NetworkTopology.empty() : networkTopology;
    componentVersions = componentVersions == null ? ComponentVersions.empty() : componentVersions;
    imageState = imageState == null ? ImageState.unknown() : imageState;
  }

  public static ManifestSynthesisRequest fromSystemProperties() {
    final String outdirProperty = System.getProperty("rk2lab.manifests.outdir");
    final String fileProperty = System.getProperty("rk2lab.manifests.file");
    final Optional<ManifestDomainPolicy> manifestDomainPolicy = policyFromSystemProperties();
    final FloxDebugPolicy floxDebugPolicy = floxDebugPolicyFromSystemProperties();

    if (outdirProperty == null && fileProperty == null) {
      return ephemeral(manifestDomainPolicy, floxDebugPolicy);
    }

    if (outdirProperty != null && fileProperty != null) {
      return new ManifestSynthesisRequest(
          Paths.get(outdirProperty),
          Paths.get(fileProperty),
          manifestDomainPolicy,
          floxDebugPolicy);
    }

    if (outdirProperty != null) {
      final Path outdir = Paths.get(outdirProperty);
      return new ManifestSynthesisRequest(
          outdir, outdir.resolve("manifests.yaml"), manifestDomainPolicy, floxDebugPolicy);
    }

    final Path manifestFile = Paths.get(fileProperty);
    final Path outdir =
        manifestFile.getParent() == null ? Paths.get(".") : manifestFile.getParent();
    return new ManifestSynthesisRequest(
        outdir, manifestFile, manifestDomainPolicy, floxDebugPolicy);
  }

  public static ManifestSynthesisRequest ephemeral() {
    return ephemeral(Optional.empty(), FloxDebugPolicy.disabled());
  }

  public static ManifestSynthesisRequest ephemeral(ManifestDomainPolicy manifestDomainPolicy) {
    return ephemeral(Optional.of(manifestDomainPolicy), FloxDebugPolicy.disabled());
  }

  private static ManifestSynthesisRequest ephemeral(
      Optional<ManifestDomainPolicy> manifestDomainPolicy, FloxDebugPolicy floxDebugPolicy) {
    try {
      final Path outdir =
          Files.createTempDirectory("rk2lab-manifests-").toAbsolutePath().normalize();
      final Path manifestFile = outdir.resolve("manifests.yaml");
      return new ManifestSynthesisRequest(
              outdir, manifestFile, manifestDomainPolicy, floxDebugPolicy)
          .withComponentVersions(ComponentVersions.defaults());
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create temporary synthesis directory", ex);
    }
  }

  public ManifestSynthesisRequest withManifestDomainPolicy(ManifestDomainPolicy policy) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        Optional.of(policy),
        floxDebugPolicy,
        bootstrapIdentity,
        networkTopology,
        componentVersions,
        imageState);
  }

  public ManifestSynthesisRequest withFloxDebugPolicy(FloxDebugPolicy policy) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        policy,
        bootstrapIdentity,
        networkTopology,
        componentVersions,
        imageState);
  }

  public ManifestSynthesisRequest withBootstrapIdentity(BootstrapIdentity identity) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        floxDebugPolicy,
        identity,
        networkTopology,
        componentVersions,
        imageState);
  }

  public ManifestSynthesisRequest withNetworkTopology(NetworkTopology topology) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        floxDebugPolicy,
        bootstrapIdentity,
        topology,
        componentVersions,
        imageState);
  }

  public ManifestSynthesisRequest withComponentVersions(ComponentVersions versions) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        floxDebugPolicy,
        bootstrapIdentity,
        networkTopology,
        versions,
        imageState);
  }

  public ManifestSynthesisRequest withImageState(ImageState state) {
    return new ManifestSynthesisRequest(
        synthOutdir,
        synthManifestFile,
        manifestDomainPolicy,
        floxDebugPolicy,
        bootstrapIdentity,
        networkTopology,
        componentVersions,
        state);
  }

  private static FloxDebugPolicy floxDebugPolicyFromSystemProperties() {
    return new FloxDebugPolicy(
        boolSystemProperty("rk2lab.manifests.policy.debug.mesh.enabled"),
        boolSystemProperty("rk2lab.manifests.policy.debug.networking.enabled"),
        boolSystemProperty("rk2lab.manifests.policy.debug.nriPlugins.flox.enabled"));
  }

  private static boolean boolSystemProperty(final String key) {
    final String value = System.getProperty(key);
    if (value == null || value.isBlank()) {
      return false;
    }
    return switch (value.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      default -> false;
    };
  }

  private static Optional<ManifestDomainPolicy> policyFromSystemProperties() {
    final String enabledDomainsProperty = System.getProperty(ENABLED_DOMAINS_PROPERTY);
    if (enabledDomainsProperty == null || enabledDomainsProperty.isBlank()) {
      return Optional.empty();
    }

    return Optional.of(
        ManifestDomainPolicy.builder()
            .domainCatalog(MANIFEST_DOMAIN_CATALOG)
            .enableOnly(
                Arrays.stream(enabledDomainsProperty.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList())
            .build());
  }
}
