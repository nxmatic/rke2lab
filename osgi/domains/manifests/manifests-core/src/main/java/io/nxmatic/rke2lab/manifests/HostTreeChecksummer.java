package io.nxmatic.rke2lab.manifests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Walks a materialised replica tree and reduces it to per-file checksums — the FACET a {@code
 * HostManifest} publishes to the cellar (docs/architecture/osgi/host-cellar-realisation-spec.adoc §
 * The host tree the instance mounts). It reads bytes but keeps none: the map is {@code relative
 * path → SHA-256}, the complete description the cellar holds so the incus prep can validate the FS
 * without the cellar ever storing content.
 *
 * <p>Keys are the tree-relative POSIX path (forward slashes, stable across OSes) so the manifest is
 * comparable regardless of where the tree was rooted; the map is sorted so the serialised form is
 * deterministic (two identical trees → identical payload → a free no-op discriminant).
 */
public final class HostTreeChecksummer {

  private static final String ALGORITHM = "SHA-256";

  /**
   * Checksum every regular file under {@code root}, keyed by its {@code root}-relative POSIX path.
   */
  public Map<String, String> checksum(Path root) {
    final Map<String, String> checksums = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .forEach(file -> checksums.put(relativePosix(root, file), sha256(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot walk the replica tree under " + root, e);
    }
    return checksums;
  }

  private static String relativePosix(Path root, Path file) {
    final StringBuilder joined = new StringBuilder();
    for (Path segment : root.relativize(file)) {
      if (joined.length() > 0) {
        joined.append('/');
      }
      joined.append(segment);
    }
    return joined.toString();
  }

  private static String sha256(Path file) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file + " for checksum", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(ALGORITHM + " unavailable", e);
    }
  }
}
