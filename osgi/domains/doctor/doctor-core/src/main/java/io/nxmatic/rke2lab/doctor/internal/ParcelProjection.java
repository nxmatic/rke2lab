package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;

/**
 * The doctor's projection of the neutral {@link Parcel} onto its own {@link Patient}, and back —
 * the ONE place the {@code org} lives. The neutral seam carries only {@code (project, stack)} (what
 * the host can actually produce); the doctor adds its {@code org}, which is the constant {@code
 * "organization"} in this single-org system. When multi-org arrives, this is the single seam to
 * source the org from context instead of a constant — no other doctor code names it.
 *
 * <p>This is the {@code Patient ↔ Parcel} mirror adapter on the doctor side: the doctor consumes
 * the neutral {@link io.nxmatic.rke2lab.seed.broker.port.Cellar} by a {@link Parcel}, and reasons
 * internally over a {@link Patient}. No doctor type crosses to the neutral port — the projection
 * happens entirely inside the bundle realm.
 */
final class ParcelProjection {

  /** The single-org constant — the Pulumi org segment, frozen until multi-org is real. */
  static final String ORG = "organization";

  /**
   * The fixed parcel the doctor keeps its intervention ledger on — the doctor OWNS this identity
   * (it knows it holds its drift ledger on one stable stack), so it names it here rather than the
   * host. There is exactly one ledger, unkeyed by patient; {@code store}/{@code fetch} address this
   * parcel.
   */
  static final Parcel LEDGER = new Parcel("intervention-ledger", "dev");

  private ParcelProjection() {}

  /** The doctor's patient for a neutral parcel — adds the org the neutral seam does not carry. */
  static Patient patientOf(Parcel parcel) {
    return new Patient(ORG, parcel.project(), parcel.stack());
  }

  /** The neutral parcel for a doctor patient — drops the org the neutral seam does not carry. */
  static Parcel parcelOf(Patient patient) {
    return new Parcel(patient.project(), patient.stack());
  }
}
