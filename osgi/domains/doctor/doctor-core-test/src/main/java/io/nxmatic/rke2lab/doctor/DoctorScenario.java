package io.nxmatic.rke2lab.doctor;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The doctor's diagnosis behaviour, told as a scenario: given a doctor staffed with some
 * specialists and a failure presenting a symptom, when the doctor is consulted, then a remediation
 * plan is produced (a prescription, or an empty plan that still names the symptom). These stages
 * are test-only — the doctor is consulted <em>inside</em> the checkpoint stages ({@code
 * SystemdAdapterStage}, {@code ClusterReadinessStage}), never played as a standalone scenario in
 * production, so per the localisation rule they live in {@code src/test}. The exhaustive type
 * mechanics (symptom parsing, observation round-trip) stay in {@code DoctorTest}.
 */
public final class DoctorScenario {

  private static final Patient TEST_PATIENT = new Patient("organization", "rke2lab", "test");

  /** The scenario asserts plan synthesis, not history, so the held record is always empty. */
  private static final MedicalRecordRegistry EMPTY_RECORDS = p -> new MedicalRecord(p, List.of());

  private DoctorScenario() {}

  /**
   * Given: the doctor's roster of specialists and the failure (symptom + observation) it will read.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState final List<Specialist> specialists = new ArrayList<>();
    @ProvidedScenarioState Symptom symptom;
    @ProvidedScenarioState Observation observation;

    public Given a_doctor_staffed_with_a_prescribing_specialist() {
      specialists.add(new FakeSpecialist());
      return self();
    }

    public Given a_failure_presenting(@Quoted Symptom symptom) {
      this.symptom = symptom;
      this.observation =
          Observation.failed(symptom, symptom.id(), Map.of("source", "doctor-scenario"));
      return self();
    }
  }

  /** When: the doctor is consulted on the symptom, capturing the remediation plan. */
  public static class When extends Stage<When> {

    @ExpectedScenarioState List<Specialist> specialists;
    @ExpectedScenarioState Symptom symptom;
    @ExpectedScenarioState Observation observation;

    @ProvidedScenarioState RemediationPlan plan;

    public When the_doctor_is_consulted() {
      final GrantPolicy policy =
          GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, TEST_PATIENT);
      final ClinicalAccess access =
          new ClinicalAccess(
              Generalist.GENERALIST_ID, TEST_PATIENT, policy, EMPTY_RECORDS, msg -> {});
      plan =
          Generalist.builder()
              .specialists(specialists)
              .access(access)
              .build()
              .consult(symptom, observation);
      return self();
    }
  }

  /**
   * Then: assert the plan the doctor produced. Plain {@link AssertionError} (not JUnit) so the
   * stages remain runnable outside a JUnit runner, mirroring the other in-tree scenarios.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Symptom symptom;
    @ExpectedScenarioState RemediationPlan plan;

    public Then a_prescription_is_issued(@Quoted RemediationProgramRef expectedProgram) {
      final Prescription prescription =
          plan.primaryPrescription()
              .orElseThrow(
                  () -> new AssertionError("expected a prescription but the plan was empty"));
      if (!expectedProgram.equals(prescription.programRef())) {
        throw new AssertionError(
            "expected prescription "
                + expectedProgram.id()
                + " but was "
                + prescription.programRef().id());
      }
      return self();
    }

    public Then the_prescription_targets_unit(@Quoted String unit) {
      final Object actual = primaryPrescription().payload().get("unit");
      if (!unit.equals(actual)) {
        throw new AssertionError(
            "expected prescription to target unit " + unit + " but was " + actual);
      }
      return self();
    }

    public Then the_operator_hint_mentions(@Quoted String fragment) {
      final String hint = primaryPrescription().humanHint();
      if (hint == null || !hint.contains(fragment)) {
        throw new AssertionError("expected operator hint to mention \"" + fragment + "\": " + hint);
      }
      return self();
    }

    public Then no_treatment_is_offered() {
      if (plan.hasPrescriptions()) {
        throw new AssertionError("expected an empty plan but prescriptions were issued");
      }
      return self();
    }

    /** Even untreated, the plan names the symptom so the runbook can report it. */
    public Then the_plan_still_names_the_symptom() {
      if (!symptom.equals(plan.symptom())) {
        throw new AssertionError(
            "expected the plan to name " + symptom + " but named " + plan.symptom());
      }
      return self();
    }

    @Hidden
    private Prescription primaryPrescription() {
      return plan.primaryPrescription()
          .orElseThrow(() -> new AssertionError("expected a prescription but the plan was empty"));
    }
  }
}
