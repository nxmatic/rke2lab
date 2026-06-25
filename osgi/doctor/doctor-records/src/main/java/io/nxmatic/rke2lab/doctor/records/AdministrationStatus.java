package io.nxmatic.rke2lab.doctor.records;

/**
 * The outcome status of a {@link Remediator} administering a {@link Prescription} against the live
 * system — the closed set, never a free string.
 */
public enum AdministrationStatus {
  /** The treatment was applied and the remediator observed it take effect. */
  ADMINISTERED,
  /** The remediator attempted the treatment but it did not succeed (the detail says why). */
  FAILED,
  /** The remediator declined to act (e.g. a precondition was not met); no change was made. */
  SKIPPED
}
