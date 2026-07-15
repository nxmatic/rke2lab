package io.nxmatic.rke2lab.incus.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the next {@code host.<N>.staging.d} replica slot a run materialises into — the fondation of
 * the host-tree model (docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree the
 * instance mounts). A run always materialises into a staging slot; the live {@code host.live} is
 * later {@code rsync}ed from a chosen staging (the grow, not this class).
 *
 * <p>The FILESYSTEM IS THE STATE — no separate counter file: the next slot is {@code (max present N
 * + 1) mod 3}, a bounded rotation over three increments (the sliding cache R2). An empty node dir
 * yields slot 0; {@code {0,1}} present yields 2; {@code {0,1,2}} present wraps to 0 (the oldest
 * position is overwritten by the fresh replica). The slot's identity is its POSITION, never its
 * content — the content hash lives in the host-manifest as a comparison discriminant.
 *
 * <p>The slot the live currently mirrors ({@code live.syncedFrom}) is PINNED out of the rotation: a
 * fresh run must not overwrite the pivot tree the two deltas ({@code change}, {@code drift}) diff
 * against, and it is the FS face of R1 (never lose what the live mounts). {@link
 * #nextStaging(Path)} takes that pinned slot root and, when the naive {@code (max+1) mod 3} would
 * land on it, steps forward to the next free position. Callers with no live yet use {@link
 * #nextStaging()}.
 */
public final class HostSlotSelector {

  /** The bounded rotation width — three rolling staging slots (the sliding cache). */
  static final int ROTATION = 3;

  // Generation-uniform naming: host.<N>.staging.d is the replica tree of generation N (its change /
  // drift deltas are sibling files host.<N>.change.{diff,json} / host.<N>.drift.{diff,json}); the
  // .d suffix marks a directory (the project convention, as in rke2-manifests.d). host.live.d is
  // the deployed tree, singular, not a generation.
  private static final Pattern SLOT = Pattern.compile("host\\.(\\d+)\\.staging\\.d");

  private static String stagingDir(int n) {
    return "host." + n + ".staging.d";
  }

  private final Path nodeRoot;

  public HostSlotSelector(Path nodeRoot) {
    this.nodeRoot = nodeRoot;
  }

  /**
   * The next staging slot to materialise into, with no slot pinned — {@code nodeRoot/host.<(max+1)
   * mod 3>.staging.d}. For the first run (no live yet) or any caller that does not pin.
   */
  public Path nextStaging() {
    return nextStaging(OptionalInt.empty());
  }

  /**
   * The next staging slot to materialise into, PINNING the slot the live currently mirrors. {@code
   * pinnedSlotRoot} is the {@code host.<N>.staging.d} root the {@code live.syncedFrom} names (the
   * host knows the slot; parsing its N here is legitimate — host vocabulary, host-side). When the
   * naive {@code (max+1) mod 3} would land on the pinned slot, step forward until a free position —
   * so the pivot tree the deltas need is never overwritten.
   */
  public Path nextStaging(Path pinnedSlotRoot) {
    return nextStaging(slotOf(pinnedSlotRoot));
  }

  private Path nextStaging(OptionalInt pinned) {
    final int start =
        maxPresentSlot().stream().map(max -> (max + 1) % ROTATION).findFirst().orElse(0);
    int next = start;
    if (pinned.isPresent() && next == pinned.getAsInt()) {
      next = (next + 1) % ROTATION;
    }
    return nodeRoot.resolve(stagingDir(next));
  }

  /** The slot number a {@code host.<N>.staging.d} root names, or empty if it is not such a root. */
  private static OptionalInt slotOf(Path stagingSlotRoot) {
    final Matcher matcher = SLOT.matcher(stagingSlotRoot.getFileName().toString());
    return matcher.matches()
        ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
        : OptionalInt.empty();
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
