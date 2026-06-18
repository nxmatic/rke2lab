package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The live doctor now holds a {@link MedicalRecordRegistry} and retrieves the patient's record as
 * the first act of every consultation (step 1, latent). These contracts lock the wiring: the record
 * is fetched for the held patient, it does not yet change the plan the doctor synthesizes, and the
 * stage can read the same record for its proof-of-wire log.
 */
class GeneralistRecordRetrievalTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "test");

  private static Generalist generalistOver(
      List<Specialist> specialists, MedicalRecordRegistry registry) {
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    return Generalist.builder().specialists(specialists).access(access).build();
  }

  /** Captures the patient it was asked about and counts calls; returns a fixed known record. */
  private static final class SpyRegistry implements MedicalRecordRegistry {
    private final MedicalRecord record;
    private Patient lastRequested;
    private int calls;

    SpyRegistry(MedicalRecord record) {
      this.record = record;
    }

    @Override
    public MedicalRecord recordFor(Patient patient) {
      this.lastRequested = patient;
      this.calls++;
      return record;
    }
  }

  @Test
  void consult_retrieves_the_held_patients_record_first() {
    final SpyRegistry spy = new SpyRegistry(new MedicalRecord(PATIENT, List.of()));
    final List<Specialist> specialists =
        List.of(new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig()));
    final Generalist generalist = generalistOver(specialists, spy);
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "doctor-scenario"));

    generalist.consult(Symptom.CONNECTION_REFUSED, observation);

    assertTrue(spy.calls >= 1, "consult must retrieve the record at least once");
    assertEquals(PATIENT, spy.lastRequested, "the record is retrieved for the held patient");
  }

  @Test
  void consult_plan_is_unchanged_by_the_retrieved_record() {
    final SpyRegistry spy = new SpyRegistry(new MedicalRecord(PATIENT, List.of()));
    final List<Specialist> specialists =
        List.of(new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig()));
    final Generalist generalist = generalistOver(specialists, spy);
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "doctor-scenario"));

    final RemediationPlan plan = generalist.consult(Symptom.CONNECTION_REFUSED, observation);

    // CONNECTION_REFUSED routes to SYSTEMD; the dbus specialist prescribes a unit restart — the
    // exact pre-change behaviour, proving the held record does not yet drive routing/synthesis.
    assertEquals(Symptom.CONNECTION_REFUSED, plan.symptom());
    assertTrue(plan.hasPrescriptions(), "the dbus specialist treats connection-refused");
    assertEquals(
        RemediationProgramRef.RESTART_UNIT, plan.primaryPrescription().orElseThrow().programRef());
  }

  @Test
  void record_for_current_patient_returns_the_registry_record() {
    final MedicalRecord known = new MedicalRecord(PATIENT, List.of());
    final SpyRegistry spy = new SpyRegistry(known);
    final Generalist generalist = generalistOver(List.of(), spy);

    assertSame(known, generalist.recordForCurrentPatient());
    assertEquals(PATIENT, spy.lastRequested);
  }
}
