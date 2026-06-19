package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.StackException;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import java.time.Instant;

/**
 * The doctor layer's own aggregator exception: reconstruction reached the end of the timeline but
 * at least one entry was unreadable. It carries the PARTIAL {@link MedicalRecord} it managed to
 * build and, via {@link #getSuppressed()}, one {@link EntryFailure} per unreadable entry. The
 * aggregator does fail-at-end; the CALLER decides — strict (rethrow) or lenient (read {@link
 * #partialRecord()} and walk the suppressed). It is never log-and-swallowed.
 */
public final class MedicalRecordReconstructionException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient MedicalRecord partialRecord;

  public MedicalRecordReconstructionException(MedicalRecord partialRecord, int failureCount) {
    super(
        "reconstruction failed for patient "
            + partialRecord.patient().qualifiedName()
            + ", "
            + failureCount
            + (failureCount == 1 ? " read failure" : " read failures")
            + "; partial record carries "
            + partialRecord.visits().size()
            + (partialRecord.visits().size() == 1 ? " visit" : " visits"));
    this.partialRecord = partialRecord;
  }

  /** The record reconstructed from the readable entries — what the caller can still proceed on. */
  public MedicalRecord partialRecord() {
    return partialRecord;
  }

  /**
   * One unreadable entry, enriched with the doctor-level identity (version + when) the bare leaf
   * lacks. The leaf {@link StackException} is the cause, so the human reads identity here and, via
   * {@code getCause().path()}, the offending file — without reproducing the failure.
   */
  public static final class EntryFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int version;
    private final transient Instant when;

    public EntryFailure(StackHistory.Entry entry, StackException cause) {
      super(
          "entry version=" + entry.version() + " at " + entry.when() + " could not be read", cause);
      this.version = entry.version();
      this.when = entry.when();
    }

    public int version() {
      return version;
    }

    public Instant when() {
      return when;
    }
  }
}
