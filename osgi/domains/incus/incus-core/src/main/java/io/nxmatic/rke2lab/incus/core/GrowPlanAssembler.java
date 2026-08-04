package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.nxmatic.rke2lab.incus.ingress.GrowImageView;
import io.nxmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.nxmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Assembles the ONE immutable {@link InstanceGrowPlan} the host GROW fetches — the sealing act of
 * the incus PREPARE (§ host-cellar-realisation, the scion-projects/host-actualises rule). Every
 * value it carries is computed OSGi-side so the host actualises without computing anything of the
 * domain: the image {@code buildChecksum} (the edge {@code recipeDigest} folded with the image
 * scalars + the nix source digest) and the two readable artifact paths. The NixOS {@code node-base}
 * substrate bakes the node's config, so the plan carries no host mounts and no cloud-init seed.
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
  private final Path imageSourceRoot;
  private final Path sharedFolder;

  public GrowPlanAssembler(
      String imageAlias,
      String builderBinary,
      String builderHost,
      String recipeDigest,
      Path imageSourceRoot,
      Path sharedFolder) {
    this.imageAlias = imageAlias;
    this.builderBinary = builderBinary;
    this.builderHost = builderHost;
    this.recipeDigest = recipeDigest;
    this.imageSourceRoot = imageSourceRoot;
    this.sharedFolder = sharedFolder;
  }

  /** Seal the network view, the image and the identity into the immutable plan. */
  public InstanceGrowPlan assemble(GrowNetworkView network, GrowIdentityView identity) {
    return new InstanceGrowPlan(network, imageView(), identity);
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
   * edge {@code recipeDigest} (the build METHOD — how nix is invoked) folded with the three image
   * scalars AND the {@code imageSourceDigest} (the build CONTENT — what {@code
   * nixosConfigurations.rke2-node-base} evaluates to). The recipe digest is mode-invariant and
   * bundle-only, so it cannot see the workspace's nix sources; the scion holds the worktree and
   * folds them here, so editing {@code node-base.nix} or bumping {@code flake.lock} moves the
   * checksum and {@code replaceOnChanges} recreates the instance onto the new image.
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
    digest.update((byte) '\n');
    digest.update(imageSourceDigest().getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * SHA-256 of the nix sources that determine the built image — {@code flake.lock} (the pinned
   * inputs: nixpkgs, flox, flox-runtime), {@code flake.nix} (the nixosConfiguration wiring), and
   * every file under {@code nixos/} (the modules) — folded over the sorted set with path + NUL +
   * bytes, so two identical trees hash identically. A missing file/dir contributes nothing but the
   * digest stays stable. Read-only: no shelling, so it is identical whether the run cultivates or
   * surveys.
   */
  private String imageSourceDigest() {
    final MessageDigest digest = sha256();
    foldFile(digest, imageSourceRoot.resolve("flake.lock"));
    foldFile(digest, imageSourceRoot.resolve("flake.nix"));
    final Path nixosDir = imageSourceRoot.resolve("nixos");
    if (Files.isDirectory(nixosDir)) {
      try (Stream<Path> files = Files.walk(nixosDir)) {
        files.filter(Files::isRegularFile).sorted().forEach(file -> foldFile(digest, file));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot walk the nix sources under " + nixosDir, ex);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void foldFile(MessageDigest digest, Path file) {
    if (!Files.isRegularFile(file)) {
      return;
    }
    digest.update((byte) '\n');
    digest.update(imageSourceRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(readAll(file));
  }

  /**
   * The artifact dir {@code <sharedFolder>/<alias>} the {@code new Image} sources from, resolved to
   * the readable copy — probes canonical path and its {@code .nfs} sibling (a mirror may live there
   * for NFS-exported directories). The scion sees the same filesystem as the host (Felix embedded
   * in the host JVM), so it probes and projects the resolved path; the host only reads it. Falls
   * back to the canonical dir when no candidate holds both artifacts (the plan still names it; a
   * preview run has no artifacts yet). The root is canonicalised at the source (JgitWorktree), so
   * no need to probe multiple forms (e.g., with/without {@code /private/} prefixes).
   */
  private Path resolveReadableArtifactDir() {
    final Path canonical = sharedFolder.resolve(imageAlias).toAbsolutePath().normalize();
    if (Files.exists(canonical.resolve(METADATA_FILENAME))
        && Files.exists(canonical.resolve(ROOTFS_FILENAME))) {
      return canonical;
    }
    final Path nfsCandidate = nfsSibling(canonical);
    if (Files.exists(nfsCandidate.resolve(METADATA_FILENAME))
        && Files.exists(nfsCandidate.resolve(ROOTFS_FILENAME))) {
      return nfsCandidate;
    }
    return canonical;
  }

  private Path nfsSibling(Path path) {
    final Path name = path.getFileName();
    return name == null ? path : path.resolveSibling(name + ".nfs").normalize();
  }

  private byte[] readAll(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read nix source file " + file, ex);
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
