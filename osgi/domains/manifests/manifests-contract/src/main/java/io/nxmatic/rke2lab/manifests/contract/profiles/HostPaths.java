// @codebase
package io.nxmatic.rke2lab.manifests.contract.profiles;

import java.nio.file.Path;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Host filesystem path slice published to synth-time domains via {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext}. Carries the {@code /srv/host} directories
 * a node-env contributor writes under ({@code RKE2LAB_ROOT}, {@code RKE2LAB_SCRIPTS_DIR}, …).
 *
 * <p>Unlike {@link NetworkTopology}, paths have no unbound state: there is no blank default, so the
 * whole set is bound at once through the {@link Builder}, which fails fast if a directory was never
 * set. A different context (operator-local, container, remote) builds its own set under its root.
 */
public record HostPaths(
    Path rootPath,
    Path envDirPath,
    Path scriptsDirPath,
    Path systemdDirPath,
    Path configDirPath,
    Path cloudconfigNocloudDirPath,
    Path manifestsDirPath,
    Path sharedDirPath) {

  public static Builder builder() {
    return new Builder();
  }

  /**
   * The recommended construction path: names each directory so the same-typed paths can't be
   * positionally swapped. Fields are {@link MonotonicNonNull} — null until set, then read once at
   * {@link #build()}, which fails fast on any that was never bound.
   */
  public static final class Builder {
    @MonotonicNonNull private Path rootPath;
    @MonotonicNonNull private Path envDirPath;
    @MonotonicNonNull private Path scriptsDirPath;
    @MonotonicNonNull private Path systemdDirPath;
    @MonotonicNonNull private Path configDirPath;
    @MonotonicNonNull private Path cloudconfigNocloudDirPath;
    @MonotonicNonNull private Path manifestsDirPath;
    @MonotonicNonNull private Path sharedDirPath;

    private Builder() {}

    public Builder rootPath(final Path v) {
      this.rootPath = v;
      return this;
    }

    public Builder envDirPath(final Path v) {
      this.envDirPath = v;
      return this;
    }

    public Builder scriptsDirPath(final Path v) {
      this.scriptsDirPath = v;
      return this;
    }

    public Builder systemdDirPath(final Path v) {
      this.systemdDirPath = v;
      return this;
    }

    public Builder configDirPath(final Path v) {
      this.configDirPath = v;
      return this;
    }

    public Builder cloudconfigNocloudDirPath(final Path v) {
      this.cloudconfigNocloudDirPath = v;
      return this;
    }

    public Builder manifestsDirPath(final Path v) {
      this.manifestsDirPath = v;
      return this;
    }

    public Builder sharedDirPath(final Path v) {
      this.sharedDirPath = v;
      return this;
    }

    public HostPaths build() {
      return new HostPaths(
          Objects.requireNonNull(rootPath, "rootPath"),
          Objects.requireNonNull(envDirPath, "envDirPath"),
          Objects.requireNonNull(scriptsDirPath, "scriptsDirPath"),
          Objects.requireNonNull(systemdDirPath, "systemdDirPath"),
          Objects.requireNonNull(configDirPath, "configDirPath"),
          Objects.requireNonNull(cloudconfigNocloudDirPath, "cloudconfigNocloudDirPath"),
          Objects.requireNonNull(manifestsDirPath, "manifestsDirPath"),
          Objects.requireNonNull(sharedDirPath, "sharedDirPath"));
    }
  }
}
