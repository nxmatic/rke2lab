package io.nxmatic.rke2lab.incus.edge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The edge-owned image-build recipe — the {@code remote-build-incus-image.sh} driver and the {@code
 * incus-distrobuilder.yaml} distrobuilder config, both bundle resources. The single owner of the
 * recipe bytes: the {@link #digest()} folds both, so a change to EITHER invalidates the host's
 * image cache, and it is the SAME digest whether the run cultivates (builds for real) or surveys
 * (plans only) — the host cache key must not move between the two. Shared by the {@code
 * Cultivating}/{@code Surveying} builder pair so neither owns the resource plumbing alone.
 */
final class BuildRecipe {

  static final String REMOTE_BUILD_SCRIPT_RESOURCE =
      "io/nxmatic/rke2lab/incus/edge/remote-build-incus-image.sh";
  static final String DISTROBUILDER_CONFIG_RESOURCE =
      "io/nxmatic/rke2lab/incus/edge/incus-distrobuilder.yaml";
  static final String CONFIG_FILENAME = "incus-distrobuilder.yaml";

  /** A stable SHA-256 over both recipe resources — the host's image-cache key, mode-invariant. */
  String digest() {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(load(REMOTE_BUILD_SCRIPT_RESOURCE));
      digest.update((byte) '\n');
      digest.update(load(DISTROBUILDER_CONFIG_RESOURCE));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new ImageBuildException("SHA-256 is not available", ex);
    }
  }

  byte[] load(String resource) {
    try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new ImageBuildException("Bundle resource not found: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to load bundle resource: " + resource, ex);
    }
  }
}
