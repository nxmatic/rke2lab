package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.ArrayList;
import java.util.List;

/**
 * The doctor's coordinator. When a checkpoint fails, the patient consults: the Generalist takes the
 * symptom + the captured {@link Observation}, and synthesizes a {@link RemediationPlan}.
 *
 * <ol>
 *   <li><b>firstLook</b> — retrieves the patient's {@link MedicalRecord} from the held registry
 *       (the first act of the visit); in step 1 it is available but does not yet drive routing.
 *   <li><b>route</b> — otherwise route <em>deterministically</em> by symptom to the relevant
 *       specialists (a readable, testable rules table — no inference). Irrelevant specialists stay
 *       dormant.
 *   <li><b>synthesize</b> — collect each routed specialist's prescription (if any) into one plan.
 * </ol>
 *
 * The Generalist holds its specialists by the {@link Specialist} interface, so it is unaware
 * whether each is a Java impl or a future out-of-process one — the AI-ready seam.
 */
public final class Generalist {

  private final List<Specialist> specialists;
  private final MedicalRecordRegistry records;
  private final Patient currentPatient;

  public Generalist(
      List<Specialist> specialists, MedicalRecordRegistry records, Patient currentPatient) {
    this.specialists = List.copyOf(specialists);
    this.records = records;
    this.currentPatient = currentPatient;
  }

  /**
   * The held registry's record for the patient under care. Memoized per patient by the registry, so
   * the stage may read it for a proof-of-wire log without double-reading the consultation's own
   * retrieval.
   */
  public MedicalRecord recordForCurrentPatient() {
    return records.recordFor(currentPatient);
  }

  /**
   * The patient consults: diagnose the symptom against the observation, return a remediation plan.
   */
  public RemediationPlan consult(Symptom symptom, Observation observation) {
    final MedicalRecord record = records.recordFor(currentPatient);
    firstLook(record, symptom, observation);
    final List<Specialty> route = routeBySymptom(symptom);
    if (route.isEmpty()) {
      return new RemediationPlan(
          symptom, List.of(), "no specialist routes for symptom " + symptom.id());
    }

    final List<Prescription> prescriptions = new ArrayList<>();
    for (Specialist specialist : specialists) {
      if (route.contains(specialist.domain())) {
        specialist.diagnose(symptom, observation).ifPresent(prescriptions::add);
      }
    }

    final String summary =
        prescriptions.isEmpty()
            ? "consulted " + route + " for " + symptom.id() + "; no treatment offered"
            : prescriptions.size() + " prescription(s) for " + symptom.id() + " from " + route;
    return new RemediationPlan(symptom, prescriptions, summary);
  }

  private void firstLook(MedicalRecord record, Symptom symptom, Observation observation) {
    // step 1: record retrieved + available; step-2 reasoning attaches here
  }

  /**
   * Deterministic symptom → domain routing. A readable rules table, not inference: a symptom maps
   * to the domains whose specialists could treat it. Unknown symptoms route nowhere (empty plan).
   */
  private static List<Specialty> routeBySymptom(Symptom symptom) {
    return switch (symptom) {
      case CONNECTION_REFUSED -> List.of(Specialty.SYSTEMD, Specialty.NETWORK);
      case TIMEOUT -> List.of(Specialty.NETWORK);
      // Cluster-readiness symptoms are typed and named in the runbook from Increment D; no
      // specialist treats them yet, so they route to the CLUSTER domain and yield an empty plan
      // (symptom seen, no treatment offered) until a cluster specialist is added.
      case KUBECONFIG_MISSING, CONTROLLER_NOT_READY -> List.of(Specialty.CLUSTER);
      case API_NOT_READY -> List.of(Specialty.CLUSTER, Specialty.NETWORK);
    };
  }
}
