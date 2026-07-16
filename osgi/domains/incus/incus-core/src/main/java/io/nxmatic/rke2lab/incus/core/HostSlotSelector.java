package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.IncusCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the next {@code host.<N>.staging.d} replica slot a run materialises into — the fondation of
 * the host-tree model (docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree the
 * instance mounts). A run always materialises into a staging slot; the live {@code host.live} is
 * later {@code rsync}ed from a chosen staging (the grow, not this class).
 *
 * <p>The CELLAR IS THE STATE — not the filesystem (corrected 2026-07-16, § HostSlotSelector reads
 * the cellar): the occupancy is the {@code host-staging} entries the run's {@link Cellar} holds
 * (its own in-flight stores overlay the durable ones, so a slot this run just published counts),
 * and the pinned {@code live.syncedFrom} the {@code host-live} entry's path. The next slot is
 * {@code (max present N + 1) mod 3}, a bounded rotation over three increments (the sliding cache
 * R2). No {@code host-staging} entry yet yields slot 0; {@code {0,1}} present yields 2; {@code
 * {0,1,2}} present wraps to 0 (the oldest position is overwritten by the fresh replica). The slot's
 * identity is its POSITION, never its content — the content hash lives in the host-manifest as a
 * comparison discriminant.
 *
 * <p>The slot the live currently mirrors ({@code live.syncedFrom}) is PINNED out of the rotation: a
 * fresh run must not overwrite the pivot tree the two deltas ({@code change}, {@code drift}) diff
 * against, and it is R1 (never lose what the live mounts). When the naive {@code (max+1) mod 3}
 * would land on it, the rotation steps forward to the next free position. The pinned slot is read
 * from the cellar's {@code host-live} entry, not passed in — the selector owns the whole occupancy
 * read.
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
  private final Cellar cellar;
  private final Parcel parcel;

  public HostSlotSelector(Path nodeRoot, Cellar cellar, Parcel parcel) {
    this.nodeRoot = nodeRoot;
    this.cellar = cellar;
    this.parcel = parcel;
  }

  /**
   * The next staging slot to materialise into — {@code nodeRoot/host.<(max+1) mod 3>.staging.d},
   * stepping past the pinned {@code live.syncedFrom} slot when the naive rotation would land on it.
   * The occupancy is the {@code host-staging} entries the run's cellar holds; the pinned slot is
   * the {@code host-live} entry's {@code syncedFrom}, empty for a first run.
   */
  public Path nextStaging() {
    final OptionalInt pinned =
        cellar
            .fetch(parcel, IncusCoordinate.HOST_LIVE, HostLiveEntry.class)
            .map(HostLiveEntry::syncedFrom)
            .map(HostSlotSelector::slotOf)
            .orElse(OptionalInt.empty());
    final int start =
        maxPresentSlot().stream().map(max -> (max + 1) % ROTATION).findFirst().orElse(0);
    int next = start;
    if (pinned.isPresent() && next == pinned.getAsInt()) {
      next = (next + 1) % ROTATION;
    }
    return nodeRoot.resolve(stagingDir(next));
  }

  /** The slot number a {@code host.<N>.staging.d} root names, or empty if it is not such a root. */
  private static OptionalInt slotOf(String stagingSlotRoot) {
    final Matcher matcher = SLOT.matcher(Path.of(stagingSlotRoot).getFileName().toString());
    return matcher.matches()
        ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
        : OptionalInt.empty();
  }

  /** The highest staging slot N the cellar holds, or empty when it holds none yet. */
  private OptionalInt maxPresentSlot() {
    return cellar.fetch(parcel, HostStagingEntry.class).stream()
        .map(HostStagingEntry::stagingRoot)
        .map(HostSlotSelector::slotOf)
        .filter(OptionalInt::isPresent)
        .mapToInt(OptionalInt::getAsInt)
        .max();
  }
}
