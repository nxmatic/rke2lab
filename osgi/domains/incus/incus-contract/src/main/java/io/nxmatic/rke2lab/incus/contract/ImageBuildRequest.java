package io.nxmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link ImageBuilder} needs to build the seed image's distrobuilder
 * artifacts, projected by the host out of its own configuration. A seam record — no host config
 * type crosses; every field is a scalar the host resolves at the call site.
 *
 * <p>Two path families: the {@code local*} paths drive a local {@code distrobuilder build-incus}
 * (when the binary is on {@code PATH}); the {@code remote*} paths drive the ssh recipe on {@code
 * remoteHost} (when it is not). The edge decides which by probing for the binary. {@code
 * remoteHost} may be blank — the edge then requires the binary locally or fails. {@code
 * remoteWorkspaceDir} is the worktree root rebased onto the builder's NFS automount view (the dir
 * the recipe {@code cd}s into); {@code remoteArtifactDir} is RELATIVE to it — the recipe joins the
 * two, so the host never second-translates the artifact subpath.
 *
 * <p>The distrobuilder CONFIG is NOT a coordinate here: the edge owns it as a bundle resource (the
 * single source of the recipe, folded into {@code recipeDigest()}), so it materialises the config
 * itself — locally to a temp file, remotely over the ssh channel. Only the artifact/workspace
 * placement crosses.
 */
public record ImageBuildRequest(
    String builderBinary,
    String workspaceDir,
    String localArtifactDir,
    String remoteHost,
    String remoteWorkspaceDir,
    String remoteArtifactDir) {

  public ImageBuildRequest {
    builderBinary = normalize(builderBinary);
    workspaceDir = normalize(workspaceDir);
    localArtifactDir = normalize(localArtifactDir);
    remoteHost = normalize(remoteHost);
    remoteWorkspaceDir = normalize(remoteWorkspaceDir);
    remoteArtifactDir = normalize(remoteArtifactDir);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
