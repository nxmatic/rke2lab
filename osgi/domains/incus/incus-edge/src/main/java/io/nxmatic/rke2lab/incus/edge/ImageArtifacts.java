package io.nxmatic.rke2lab.incus.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The seed image's built artifacts on the builder's filesystem — visible to the host at {@code
 * directory} over the NFS automount — plus the recipe-digest marker that gates a rebuild.
 *
 * <p>Instance-passing: the edge constructs one from the artifact dir and the recipe digest, then
 * asks {@link #areFresh()} before shelling the (expensive) nix build and {@link #seal()} after a
 * successful build. An unchanged recipe reuses the existing artifacts instead of rebuilding on
 * every {@code up}; a changed nix build script moves the digest and forces exactly one rebuild.
 * Immutable — {@link #seal()} writes the filesystem, never this value.
 */
record ImageArtifacts(Path directory, String recipeDigest) {

  private static final String METADATA_FILENAME = "incus.tar.xz";
  private static final String ROOTFS_FILENAME = "rootfs.squashfs";
  private static final String RECIPE_MARKER_FILENAME = ".recipe-digest";

  /** Whether both artifacts are present AND were built from this exact recipe (marker matches). */
  boolean areFresh() {
    if (!Files.isRegularFile(directory.resolve(METADATA_FILENAME))
        || !Files.isRegularFile(directory.resolve(ROOTFS_FILENAME))) {
      return false;
    }
    try {
      return recipeDigest.equals(
          Files.readString(directory.resolve(RECIPE_MARKER_FILENAME), StandardCharsets.UTF_8)
              .trim());
    } catch (IOException absentOrUnreadable) {
      return false;
    }
  }

  /** Stamp the recipe marker so the next run reuses these artifacts while the recipe holds. */
  void seal() {
    try {
      Files.writeString(
          directory.resolve(RECIPE_MARKER_FILENAME), recipeDigest, StandardCharsets.UTF_8);
    } catch (IOException missingMarkerOnlyCostsARebuild) {
      // A marker we could not write only forces a rebuild next run — never fail a good build.
    }
  }
}
