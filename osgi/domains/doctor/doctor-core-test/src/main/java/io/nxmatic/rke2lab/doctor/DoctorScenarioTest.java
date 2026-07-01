package io.nxmatic.rke2lab.doctor;

import com.tngtech.jgiven.junit5.ScenarioTest;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import org.junit.jupiter.api.Test;

/**
 * The doctor's diagnosis behaviour, told as scenarios: a presenting symptom is consulted and the
 * doctor returns a remediation plan. The same Generalist/Specialist/Observation vocabulary the
 * checkpoints use in production is the scenario's DSL. Type-level contracts (symptom parsing,
 * observation round-trip, a specialist declining an unrelated symptom) stay in {@code DoctorTest}.
 */
class DoctorScenarioTest
    extends ScenarioTest<DoctorScenario.Given, DoctorScenario.When, DoctorScenario.Then> {

  @Test
  void connection_refused_is_prescribed_a_unit_restart() {
    given()
        .a_doctor_staffed_with_a_prescribing_specialist()
        .and()
        .a_failure_presenting(Symptom.CONNECTION_REFUSED);
    when().the_doctor_is_consulted();
    then()
        .a_prescription_is_issued(RemediationProgramRef.RESTART_UNIT)
        .and()
        .the_prescription_targets_unit(FakeSpecialist.UNIT)
        .and()
        .the_operator_hint_mentions("systemctl restart");
  }

  @Test
  void a_symptom_no_specialist_covers_yields_an_empty_but_named_plan() {
    // TIMEOUT routes to the NETWORK domain, which the prescribing specialist does not cover.
    given()
        .a_doctor_staffed_with_a_prescribing_specialist()
        .and()
        .a_failure_presenting(Symptom.TIMEOUT);
    when().the_doctor_is_consulted();
    then().no_treatment_is_offered().and().the_plan_still_names_the_symptom();
  }

  // Increment D: the cluster symptoms are typed and routed so they are named in the runbook, but no
  // specialist treats them yet — the doctor returns an empty plan that still names the symptom, not
  // a crash. One scenario per symptom (rather than a parameterized case) so each renders as its own
  // line in the runbook and we stay on JGiven's own fluent API.

  @Test
  void a_missing_kubeconfig_is_recognized_but_not_yet_treated() {
    recognized_but_untreated(Symptom.KUBECONFIG_MISSING);
  }

  @Test
  void an_unready_api_is_recognized_but_not_yet_treated() {
    recognized_but_untreated(Symptom.API_NOT_READY);
  }

  @Test
  void an_ineffective_controller_is_recognized_but_not_yet_treated() {
    recognized_but_untreated(Symptom.CONTROLLER_NOT_READY);
  }

  private void recognized_but_untreated(Symptom symptom) {
    given().a_doctor_staffed_with_a_prescribing_specialist().and().a_failure_presenting(symptom);
    when().the_doctor_is_consulted();
    then().no_treatment_is_offered().and().the_plan_still_names_the_symptom();
  }
}
