package io.seedmatic.rke2lab.pulumi.edge;

import java.util.List;
import java.util.Optional;

/**
 * The single read seam between the diagnostic model and whatever observes the running system: a
 * timeline of entries plus the ability to materialize the snapshot at any of them. The model
 * depends on this port, not on a backend, so a test injects a fake and the host provides the real
 * adapter (DIP, instance-passing discipline).
 *
 * <p>Error contract follows the layered stance: every method returns nothing-here (empty list /
 * empty optional) for genuine absence, but throws {@link SnapshotAccessException}/{@link
 * SnapshotContentException} when something that should be readable cannot be read — an absent
 * timeline is empty, a present-but-unreadable one throws. Errors are never masked as empty.
 */
public interface SnapshotSource {

  /**
   * The ordered timeline entries, ascending by deployment time. An absent timeline yields an empty
   * list (a legitimate nothing-here); a timeline that exists but cannot be read throws — the spine
   * is the precondition for any reconstruction, so its failure is not nothing-here.
   */
  List<SnapshotEntry> timeline() throws SnapshotAccessException, SnapshotContentException;

  /**
   * Materializes the snapshot for an entry from {@link #timeline()}. Because the entry is known to
   * exist, a read failure is exceptional and propagates rather than degrading to empty.
   */
  SnapshotView at(SnapshotEntry entry) throws SnapshotAccessException, SnapshotContentException;

  /**
   * The current snapshot, or empty when no current state exists (nothing-here). A present-but-
   * unreadable current state throws rather than masquerading as empty.
   */
  Optional<SnapshotView> latest() throws SnapshotAccessException, SnapshotContentException;
}
