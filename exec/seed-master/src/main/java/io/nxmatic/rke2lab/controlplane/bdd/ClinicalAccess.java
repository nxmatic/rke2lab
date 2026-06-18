package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;
import java.util.function.Consumer;

/**
 * The grant-checked, id-bound view a {@link Clinician} reads records through. The {@link
 * HealthSystem} mints one per clinician at employment, with the clinician's {@link ClinicianId}
 * <em>closed over</em> (not a call parameter) — so a clinician cannot read as another id, and
 * cannot mint its own access.
 *
 * <p>A read that is not granted degrades to an empty {@code MedicalRecord(patient, List.of())} and
 * a logged reason; it never throws into the diagnosis path (mirroring the registry contract). The
 * gate is trivial for the admitted patient's own record (admission self-grants it) and genuinely
 * bites the cohort: {@link #cohort()} returns only the siblings this id holds grants on.
 */
public final class ClinicalAccess {

  private final ClinicianId boundId;
  private final Patient admittedPatient;
  private final GrantPolicy policy;
  private final MedicalRecordRegistry registry;
  private final Consumer<String> logger;

  public ClinicalAccess(
      ClinicianId boundId,
      Patient admittedPatient,
      GrantPolicy policy,
      MedicalRecordRegistry registry,
      Consumer<String> logger) {
    this.boundId = boundId;
    this.admittedPatient = admittedPatient;
    this.policy = policy;
    this.registry = registry;
    this.logger = logger;
  }

  /** The admitted patient's own record (self-granted at admission). */
  public MedicalRecord record() {
    return record(admittedPatient);
  }

  /** Any patient's record, served only when {@code boundId} holds a grant for it. */
  public MedicalRecord record(Patient patient) {
    if (!policy.isGranted(boundId, patient)) {
      logger.accept(
          "access denied for "
              + boundId.value()
              + " on "
              + patient.qualifiedName()
              + ": no grant — returning empty record");
      return new MedicalRecord(patient, List.of());
    }
    return registry.recordFor(patient);
  }

  /**
   * The granted subset of the admitted patient's cohort — the siblings this id may correlate over.
   */
  public List<MedicalRecord> cohort() {
    return registry.cohortFor(admittedPatient).stream()
        .filter(rec -> policy.isGranted(boundId, rec.patient()))
        .toList();
  }
}
