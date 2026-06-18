package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.StackAccessException;
import io.nxmatic.rke2lab.pulumi.automation.StackContentException;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * The single read seam between {@link MedicalRecordReader} and the Pulumi backend: a timeline of
 * history entries plus the ability to materialize the snapshot at any of them. The reader depends
 * on this interface, not on the backend, so a test injects a fake (instance-passing discipline).
 *
 * <p>Error contract follows the layered stance: every method returns nothing-here (empty list /
 * empty optional) for genuine absence, but throws {@link StackAccessException}/{@link
 * StackContentException} when something that should be readable cannot be read — an absent history
 * is empty, a present-but-unreadable one throws. Errors are never masked as empty.
 */
interface SnapshotSource {

  /**
   * The ordered history entries, ascending by deployment time. An absent history yields an empty
   * list (a legitimate nothing-here); a history that exists but cannot be read (I/O, malformed)
   * throws — the spine is the precondition for any reconstruction, so its failure is not
   * nothing-here.
   */
  List<StackHistory.Entry> timeline() throws StackAccessException, StackContentException;

  /**
   * Materializes the snapshot for an entry from {@link #timeline()}. Because the entry is known to
   * exist, a read failure is exceptional and propagates rather than degrading to empty.
   */
  StackSnapshot at(StackHistory.Entry entry) throws StackAccessException, StackContentException;

  /**
   * The current snapshot, or empty when no current state exists (nothing-here). A present-but-
   * unreadable current state throws rather than masquerading as empty.
   */
  Optional<StackSnapshot> latest() throws StackAccessException, StackContentException;
}
