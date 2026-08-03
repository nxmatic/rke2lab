package io.nxmatic.rke2lab.incus.contract;

import java.util.Optional;

/**
 * The incus domain's image-build contact seam: produce the seed image's Incus artifacts ({@code
 * incus.tar.xz} + {@code rootfs.squashfs}) in the request's artifact directory. The {@code
 * incus-image-edge} provides it by running the bundled nix build script locally when {@code nix}
 * and {@code incus} both resolve on {@code PATH}, otherwise by streaming that script over {@code
 * ssh} to the builder host. The host keeps the Pulumi {@code Image} resource, the artifact cache,
 * and the config-derived path translation, and composes this contact only when a rebuild is
 * actually needed.
 *
 * <p>The grain is coarse — one call runs a whole build — but it owns no caching and no policy: it
 * builds when asked and throws on failure.
 */
public interface ImageBuilder {

  /**
   * Build the artifacts described by {@code request}, blocking until they land in the artifact
   * directory. Returns {@link Optional#empty()} on success; otherwise a short human summary of why
   * the build failed (binary missing with no remote host configured, a non-zero build exit, an ssh
   * failure). Never throws for a build failure.
   */
  Optional<String> build(ImageBuildRequest request);

  /**
   * A stable digest of the edge-owned build recipe (the remote build script). The host folds this
   * into its own image-cache key so that a recipe change invalidates the cache and republishes the
   * image fingerprint, without the host needing to hold the script. Deterministic across calls for
   * a given edge build.
   */
  String recipeDigest();
}
