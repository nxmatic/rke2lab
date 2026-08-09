package io.nxmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link ImageBuilder} needs to build the seed image's Incus artifacts,
 * projected by the host out of its own configuration. A seam record — no host config type crosses;
 * every field is a scalar the host resolves at the call site.
 *
 * <p>Two path families: the {@code local*} paths drive a local nix build (when {@code nix} + {@code
 * incus} both resolve on {@code PATH}); the {@code remote*} paths drive the ssh script on {@code
 * remoteHost} (when they do not). The edge decides which by probing for the binaries. {@code
 * remoteHost} may be blank — the edge then requires them locally or fails. {@code
 * remoteWorkspaceDir} is the worktree root rebased onto the builder's automount view (the dir the
 * script {@code cd}s into, and the flake it builds); {@code remoteArtifactDir} is RELATIVE to it —
 * the script joins the two, so the host never second-translates the artifact subpath.
 *
 * <p>The build SCRIPT is NOT a coordinate here: the edge owns it as a bundle resource (the single
 * source of the recipe, folded into {@code recipeDigest()}), so it materialises the script itself —
 * locally to a temp file, remotely over the ssh channel. Only the artifact/workspace placement
 * crosses.
 *
 * <p>{@code incusProject} is the daemon project the remote build registers the finished image into
 * ({@code incus image import … --project}): the builder host IS the incus daemon host, so it seeds
 * the image locally and the host GROW adopts it by alias instead of re-uploading. Blank ⇒ the edge
 * skips the daemon-side registration (a local build with no reachable daemon).
 */
public record ImageBuildRequest(
    String builderBinary,
    String workspaceDir,
    String localArtifactDir,
    String remoteHost,
    String remoteWorkspaceDir,
    String remoteArtifactDir,
    String incusProject) {

  public ImageBuildRequest {
    builderBinary = normalize(builderBinary);
    workspaceDir = normalize(workspaceDir);
    localArtifactDir = normalize(localArtifactDir);
    remoteHost = normalize(remoteHost);
    remoteWorkspaceDir = normalize(remoteWorkspaceDir);
    remoteArtifactDir = normalize(remoteArtifactDir);
    incusProject = normalize(incusProject);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
