package io.nxmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link ImageBuilder} needs to build the seed image's distrobuilder
 * artifacts, projected by the host out of its own configuration. A seam record — no host config
 * type crosses; every field is a scalar the host resolves at the call site.
 *
 * <p>Two path families: the {@code local*} paths drive a local {@code distrobuilder build-incus}
 * (when the binary is on {@code PATH}); the {@code remote*} paths (NixOS-translated by the host)
 * drive the ssh recipe on {@code remoteHost} (when it is not). The edge decides which by probing
 * for the binary. {@code remoteHost} may be blank — the edge then requires the binary locally or
 * fails.
 */
public record ImageBuildRequest(
    String builderBinary,
    String workspaceDir,
    String localConfigPath,
    String localArtifactDir,
    String remoteHost,
    String remoteWorkspaceDir,
    String remoteConfigPath,
    String remoteArtifactDir) {

  public ImageBuildRequest {
    builderBinary = normalize(builderBinary);
    workspaceDir = normalize(workspaceDir);
    localConfigPath = normalize(localConfigPath);
    localArtifactDir = normalize(localArtifactDir);
    remoteHost = normalize(remoteHost);
    remoteWorkspaceDir = normalize(remoteWorkspaceDir);
    remoteConfigPath = normalize(remoteConfigPath);
    remoteArtifactDir = normalize(remoteArtifactDir);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
