package io.nxmatic.rke2lab.incus.port;

/**
 * The incus domain's image-build contact seam: produce the seed image's distrobuilder artifacts
 * ({@code incus.tar.xz} + {@code rootfs.squashfs}) in the request's artifact directory. The {@code
 * incus-image-edge} provides it by shelling {@code distrobuilder build-incus} locally when the
 * binary is on {@code PATH}, otherwise by streaming its remote build recipe over {@code ssh} to the
 * builder host. The host keeps the Pulumi {@code Image} resource, the artifact cache, and the
 * config-derived path translation, and composes this contact only when a rebuild is actually
 * needed.
 *
 * <p>The grain is coarse — one call runs a whole build — but it owns no caching and no policy: it
 * builds when asked and throws on failure.
 */
public interface ImageBuilder {

  /**
   * Build the artifacts described by {@code request}, blocking until they land in the artifact
   * directory. Throws {@link ImageBuildException} on any failure (binary missing with no remote
   * host configured, a non-zero build exit, an ssh failure) — the host lets it propagate, aborting
   * the provision.
   */
  void build(ImageBuildRequest request);

  /**
   * A stable digest of the edge-owned build recipe (the remote build script). The host folds this
   * into its own image-cache key so that a recipe change invalidates the cache and republishes the
   * image fingerprint, without the host needing to hold the script. Deterministic across calls for
   * a given edge build.
   */
  String recipeDigest();
}
