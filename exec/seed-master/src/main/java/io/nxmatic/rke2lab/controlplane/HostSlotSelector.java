package io.nxmatic.rke2lab.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the next {@code host.staging.N} replica slot a run materialises into — the fondation of the
 * host-tree model (docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree the
 * instance mounts). A run always materialises into a staging slot; the live {@code host.live} is
 * later {@code rsync}ed from a chosen staging (the grow, not this class).
 *
 * <p>The FILESYSTEM IS THE STATE — no separate counter file: the next slot is {@code (max present N
 * + 1) mod 3}, a bounded rotation over three increments (the sliding cache R2). An empty node dir
 * yields slot 0; {@code {0,1}} present yields 2; {@code {0,1,2}} present wraps to 0 (the oldest
 * position is overwritten by the fresh replica). The slot's identity is its POSITION, never its
 * content — the content hash lives in the host-manifest as a comparison discriminant.
 */
public final class HostSlotSelector {

  /** The bounded rotation width — three rolling staging slots (the sliding cache). */
  static final int ROTATION = 3;

  private static final String STAGING_PREFIX = "host.staging.";
  private static final Pattern SLOT = Pattern.compile(Pattern.quote(STAGING_PREFIX) + "(\\d+)");

  private final Path nodeRoot;

  public HostSlotSelector(Path nodeRoot) {
    this.nodeRoot = nodeRoot;
  }

  /**
   * The next staging slot to materialise into: {@code nodeRoot/host.staging.<(max+1) mod 3>}. Reads
   * the present slots off the filesystem (the state), so successive runs rotate without any stored
   * counter.
   */
  public Path nextStaging() {
    final int next =
        maxPresentSlot().stream().map(max -> (max + 1) % ROTATION).findFirst().orElse(0);
    return nodeRoot.resolve(STAGING_PREFIX + next);
  }

  /** The highest staging slot N present on the FS, or empty when none exists yet. */
  private OptionalInt maxPresentSlot() {
    if (!Files.isDirectory(nodeRoot)) {
      return OptionalInt.empty();
    }
    try (Stream<Path> entries = Files.list(nodeRoot)) {
      return entries
          .map(path -> path.getFileName().toString())
          .map(SLOT::matcher)
          .filter(Matcher::matches)
          .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
          .max();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot enumerate staging slots under " + nodeRoot, e);
    }
  }
}
