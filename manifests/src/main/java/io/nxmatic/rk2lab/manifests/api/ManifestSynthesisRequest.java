package io.nxmatic.rk2lab.manifests.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

/** Request contract for canonical manifest synthesis. */
public record ManifestSynthesisRequest(
    Path synthOutdir, Path synthManifestFile, Optional<ManifestDomainPolicy> manifestDomainPolicy)
    implements ManifestDomainPolicyAware {

  private static final String ENABLED_DOMAINS_PROPERTY = "rk2lab.manifests.policy.enabledDomains";

  public ManifestSynthesisRequest(Path synthOutdir, Path synthManifestFile) {
    this(synthOutdir, synthManifestFile, Optional.empty());
  }

  public ManifestSynthesisRequest {
    synthOutdir = synthOutdir.toAbsolutePath().normalize();
    synthManifestFile = synthManifestFile.toAbsolutePath().normalize();
    manifestDomainPolicy =
        manifestDomainPolicy == null
            ? Optional.empty()
            : manifestDomainPolicy.map(policy -> policy);
  }

  public static ManifestSynthesisRequest fromSystemProperties() {
    final String outdirProperty = System.getProperty("rk2lab.manifests.outdir");
    final String fileProperty = System.getProperty("rk2lab.manifests.file");
    final Optional<ManifestDomainPolicy> manifestDomainPolicy = policyFromSystemProperties();

    if (outdirProperty == null && fileProperty == null) {
      return ephemeral(manifestDomainPolicy);
    }

    if (outdirProperty != null && fileProperty != null) {
      return new ManifestSynthesisRequest(
          Paths.get(outdirProperty), Paths.get(fileProperty), manifestDomainPolicy);
    }

    if (outdirProperty != null) {
      final Path outdir = Paths.get(outdirProperty);
      return new ManifestSynthesisRequest(
          outdir, outdir.resolve("manifests.yaml"), manifestDomainPolicy);
    }

    final Path manifestFile = Paths.get(fileProperty);
    final Path outdir =
        manifestFile.getParent() == null ? Paths.get(".") : manifestFile.getParent();
    return new ManifestSynthesisRequest(outdir, manifestFile, manifestDomainPolicy);
  }

  public static ManifestSynthesisRequest ephemeral() {
    return ephemeral(Optional.empty());
  }

  public static ManifestSynthesisRequest ephemeral(ManifestDomainPolicy manifestDomainPolicy) {
    return ephemeral(Optional.of(manifestDomainPolicy));
  }

  private static ManifestSynthesisRequest ephemeral(
      Optional<ManifestDomainPolicy> manifestDomainPolicy) {
    try {
      final Path outdir =
          Files.createTempDirectory("rk2lab-manifests-").toAbsolutePath().normalize();
      final Path manifestFile = outdir.resolve("manifests.yaml");
      return new ManifestSynthesisRequest(outdir, manifestFile, manifestDomainPolicy);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create temporary synthesis directory", ex);
    }
  }

  public ManifestSynthesisRequest withManifestDomainPolicy(ManifestDomainPolicy policy) {
    return new ManifestSynthesisRequest(synthOutdir, synthManifestFile, Optional.of(policy));
  }

  private static Optional<ManifestDomainPolicy> policyFromSystemProperties() {
    final String enabledDomainsProperty = System.getProperty(ENABLED_DOMAINS_PROPERTY);
    if (enabledDomainsProperty == null || enabledDomainsProperty.isBlank()) {
      return Optional.empty();
    }

    return Optional.of(
        ManifestDomainPolicy.enableOnly(
            Arrays.stream(enabledDomainsProperty.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList()));
  }
}
