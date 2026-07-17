package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.contract.host.GrowImageView;
import io.nxmatic.rke2lab.incus.contract.host.GrowNetworkView;
import io.nxmatic.rke2lab.incus.contract.host.InstanceGrowPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Assembles the ONE immutable {@link InstanceGrowPlan} the host GROW fetches — the sealing act of
 * the incus PREPARE (§ host-cellar-realisation, the scion-projects/host-actualises rule). Every
 * value it carries is computed OSGi-side so the host actualises without computing anything of the
 * domain: the image {@code buildChecksum} (the edge {@code recipeDigest} folded with the image
 * scalars), the two readable artifact paths, and the cloud-init checksum that arms the
 * nocloud→replace wire.
 *
 * <p>Instance-passing: the caller hands the {@link GrowNetworkView} the network beat already
 * resolved and the flat image scalars; this assembler folds them into the plan. No static helper —
 * its inputs are its constructor arguments, its one act is {@link #assemble}.
 */
public final class GrowPlanAssembler {

  private static final String METADATA_FILENAME = "incus.tar.xz";
  private static final String ROOTFS_FILENAME = "rootfs.squashfs";

  private final String imageAlias;
  private final String builderBinary;
  private final String builderHost;
  private final String recipeDigest;
  private final Path sharedFolder;
  private final Path cloudSeedRoot;

  public GrowPlanAssembler(
      String imageAlias,
      String builderBinary,
      String builderHost,
      String recipeDigest,
      Path sharedFolder,
      Path cloudSeedRoot) {
    this.imageAlias = imageAlias;
    this.builderBinary = builderBinary;
    this.builderHost = builderHost;
    this.recipeDigest = recipeDigest;
    this.sharedFolder = sharedFolder;
    this.cloudSeedRoot = cloudSeedRoot;
  }

  /** Seal the network view + the image + the cloud-init checksum into the immutable plan. */
  public InstanceGrowPlan assemble(GrowNetworkView network) {
    return new InstanceGrowPlan(network, imageView(), cloudInitChecksum());
  }

  private GrowImageView imageView() {
    final Path artifactDir = resolveReadableArtifactDir();
    return new GrowImageView(
        imageAlias,
        artifactDir.resolve(METADATA_FILENAME).toString(),
        artifactDir.resolve(ROOTFS_FILENAME).toString(),
        buildChecksum());
  }

  /**
   * The image-cache key the host poses on {@code user.rke2lab.imageBuildChecksum}: SHA-256 of the
   * edge {@code recipeDigest} (which already folds the build script + distrobuilder config) with
   * the three image scalars, each separated by {@code '\n'}. The scion is the only place that holds
   * all four (the recipe digest comes from the edge, OSGi-side); the host recomputes nothing.
   */
  private String buildChecksum() {
    final MessageDigest digest = sha256();
    digest.update(recipeDigest.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(imageAlias.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(builderBinary.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(builderHost.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * The artifact dir {@code <sharedFolder>/<alias>} the {@code new Image} sources from, resolved to
   * the readable copy — the same NFS-candidate probe the old host provider ran (a mirror may live
   * at a {@code .nfs} sibling or the {@code /private} form of an automount path). The scion sees
   * the same filesystem as the host (Felix embedded in the host JVM), so it probes and projects the
   * resolved path; the host only reads it. Falls back to the canonical dir when no candidate holds
   * both artifacts (the plan still names it; a preview run has no artifacts yet).
   */
  private Path resolveReadableArtifactDir() {
    final Path canonical = sharedFolder.resolve(imageAlias).toAbsolutePath().normalize();
    for (Path candidate : artifactCandidates(canonical)) {
      if (Files.exists(candidate.resolve(METADATA_FILENAME))
          && Files.exists(candidate.resolve(ROOTFS_FILENAME))) {
        return candidate;
      }
    }
    return canonical;
  }

  private List<Path> artifactCandidates(Path canonical) {
    final List<Path> candidates = new ArrayList<>();
    candidates.add(canonical);
    candidates.add(nfsSibling(canonical));
    final String raw = canonical.toString();
    if (raw.startsWith("/net/")) {
      final int privateIndex = raw.indexOf("/private/");
      if (privateIndex >= 0) {
        final Path underPrivate = Path.of(raw.substring(privateIndex)).normalize();
        candidates.add(underPrivate);
        candidates.add(nfsSibling(underPrivate));
        final Path stripped =
            Path.of(raw.substring(privateIndex + "/private".length())).normalize();
        candidates.add(stripped);
        candidates.add(nfsSibling(stripped));
      }
    }
    return candidates;
  }

  private Path nfsSibling(Path path) {
    final Path name = path.getFileName();
    return name == null ? path : path.resolveSibling(name + ".nfs").normalize();
  }

  /**
   * SHA-256 of the NoCloud seed the instance reads at first boot ({@code user-data} / {@code
   * meta-data} / {@code network-config} under {@code cloud.d}), folded over the sorted file set so
   * two identical seeds hash identically. It arms the nocloud→replace wire: the host poses it on
   * {@code user.rke2lab.provisioning.slice.cloud-init}, and {@code replaceOnChanges(config.*)}
   * recreates the instance when the seed changes. Empty when the seed dir is absent (an unamended
   * survey does not reach this assembler).
   */
  private String cloudInitChecksum() {
    final MessageDigest digest = sha256();
    if (!Files.isDirectory(cloudSeedRoot)) {
      return HexFormat.of().formatHex(digest.digest());
    }
    try (Stream<Path> files = Files.walk(cloudSeedRoot)) {
      files
          .filter(Files::isRegularFile)
          .sorted()
          .forEach(
              file -> {
                digest.update((byte) '\n');
                digest.update(
                    cloudSeedRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(readAll(file));
              });
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk the NoCloud seed under " + cloudSeedRoot, ex);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private byte[] readAll(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read NoCloud seed file " + file, ex);
    }
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
