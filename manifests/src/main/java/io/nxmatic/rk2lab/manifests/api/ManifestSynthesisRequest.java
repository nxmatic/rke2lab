package io.nxmatic.rk2lab.manifests.api;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
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
    FloxDebugPolicy floxDebugPolicy)
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

  public ManifestSynthesisRequest {
    synthOutdir = synthOutdir.toAbsolutePath().normalize();
    synthManifestFile = synthManifestFile.toAbsolutePath().normalize();
    manifestDomainPolicy =
        manifestDomainPolicy == null
            ? Optional.empty()
            : manifestDomainPolicy.map(policy -> policy);
    floxDebugPolicy = floxDebugPolicy == null ? FloxDebugPolicy.disabled() : floxDebugPolicy;
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
          outdir, manifestFile, manifestDomainPolicy, floxDebugPolicy);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create temporary synthesis directory", ex);
    }
  }

  public ManifestSynthesisRequest withManifestDomainPolicy(ManifestDomainPolicy policy) {
    return new ManifestSynthesisRequest(
        synthOutdir, synthManifestFile, Optional.of(policy), floxDebugPolicy);
  }

  public ManifestSynthesisRequest withFloxDebugPolicy(FloxDebugPolicy policy) {
    return new ManifestSynthesisRequest(
        synthOutdir, synthManifestFile, manifestDomainPolicy, policy);
  }

  private static FloxDebugPolicy floxDebugPolicyFromSystemProperties() {
    final String value =
        System.getProperty("rk2lab.manifests.policy.debug.nriPlugins.flox.enabled");
    if (value == null || value.isBlank()) {
      return FloxDebugPolicy.disabled();
    }
    return switch (value.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> FloxDebugPolicy.debug();
      default -> FloxDebugPolicy.disabled();
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
