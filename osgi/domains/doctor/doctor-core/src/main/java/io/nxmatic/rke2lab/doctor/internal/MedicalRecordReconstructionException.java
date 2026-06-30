package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.*;
import java.time.Instant;

/**
 * The doctor layer's own aggregator exception: the fold reached the end of the journal but at least
 * one {@code visit} {@link io.nxmatic.rke2lab.world.gateway.port.Document} was unreadable. It
 * carries the PARTIAL {@link MedicalRecord} it managed to build and, via {@link #getSuppressed()},
 * one {@link EntryFailure} per unreadable entry. The aggregator does fail-at-end; the CALLER
 * decides — strict (rethrow) or lenient (read {@link #partialRecord()} and walk the suppressed). It
 * is never log-and-swallowed.
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
   * One unreadable {@code visit} Document, enriched with the doctor-level identity (version + when)
   * carried in its envelope. The underlying parse/shape failure is the cause, so the human reads
   * identity here and the offending content via {@code getCause()} — without reproducing the
   * failure.
   */
  public static final class EntryFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int version;
    private final transient Instant when;

    public EntryFailure(int version, Instant when, Throwable cause) {
      super("entry version=" + version + " at " + when + " could not be read", cause);
      this.version = version;
      this.when = when;
    }

    public int version() {
      return version;
    }

    public Instant when() {
      return when;
    }
  }
}
