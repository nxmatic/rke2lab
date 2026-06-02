package io.nxmatic.rk2lab.manifests;

import java.nio.file.Path;

/**
 * Result contract for canonical manifest synthesis.
 *
 * @param manifestFile consolidated K8s manifest file (YAML)
 * @param systemdUnitsDir directory containing synthesized systemd units (.service, .target)
 * @param manifestUnitHitCount number of manifest units processed
 * @param domainCount number of domains synthesized
 */
public record ManifestSynthesisResult(
    Path manifestFile, Path systemdUnitsDir, int manifestUnitHitCount, int domainCount) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Path manifestFile;
    private Path systemdUnitsDir;
    private int manifestUnitHitCount;
    private int domainCount;

    private Builder() {}

    public Builder manifestFile(Path value) {
      this.manifestFile = value;
      return this;
    }

    public Builder systemdUnitsDir(Path value) {
      this.systemdUnitsDir = value;
      return this;
    }

    public Builder manifestUnitHitCount(int value) {
      this.manifestUnitHitCount = value;
      return this;
    }

    public Builder domainCount(int value) {
      this.domainCount = value;
      return this;
    }

    public ManifestSynthesisResult build() {
      return new ManifestSynthesisResult(
          manifestFile, systemdUnitsDir, manifestUnitHitCount, domainCount);
    }
  }
}
