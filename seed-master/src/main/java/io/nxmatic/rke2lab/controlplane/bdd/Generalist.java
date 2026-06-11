package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.ArrayList;
import java.util.List;

/**
 * The doctor's coordinator. When a checkpoint fails, the patient consults: the Generalist takes the
 * symptom + the captured {@link Observation}, and synthesizes a {@link RemediationPlan}.
 *
 * <ol>
 *   <li><b>firstLook</b> — retrieves the patient's {@link MedicalRecord} through its {@link
 *       ClinicalAccess} (the first act of the visit); it does not yet drive routing.
 *   <li><b>route</b> — route <em>deterministically</em> by symptom to the relevant specialties (a
 *       readable rules table — no inference). Irrelevant specialists stay dormant.
 *   <li><b>synthesize</b> — collect each routed specialist's prescription (if any) into one plan.
 * </ol>
 *
 * The Generalist reads records only through its {@link ClinicalAccess} (bound to its id by the
 * {@link HealthSystem} at employment); it holds no registry or patient directly.
 */
public final class Generalist implements Clinician {

  /** The Generalist's stable id — the grant policy's join key for the general practitioner. */
  public static final ClinicianId GENERALIST_ID = new ClinicianId("generalist");

  private final List<Specialist> specialists;
  private final ClinicalAccess access;

  public Generalist(List<Specialist> specialists, ClinicalAccess access) {
    this.specialists = List.copyOf(specialists);
    this.access = access;
  }

  @Override
  public ClinicianId clinicianId() {
    return GENERALIST_ID;
  }

  /** The admitted patient's record, read through the held access. */
  public MedicalRecord recordForCurrentPatient() {
    return access.record();
  }

  /**
   * A one-line cross-patient finding for the symptom, folded across the granted cohort. Empty
   * cohort (or no backend) yields a finding over just the current patient.
   */
  public String cohortFinding(Symptom symptom) {
    final List<MedicalRecord> cohort = access.cohort();
    final long withSymptom = cohort.stream().filter(r -> r.historyOf(symptom).count() > 0).count();
    final long treatedAndResolved =
        cohort.stream().filter(r -> r.efficacyOf(symptom).everWorked()).count();
    return "cohort: "
        + symptom.id()
        + " seen on "
        + withSymptom
        + " of "
        + cohort.size()
        + " patient(s); "
        + treatedAndResolved
        + " prior treatment(s) resolved it";
  }

  /**
   * The patient consults: diagnose the symptom against the observation, return a remediation plan.
   */
  public RemediationPlan consult(Symptom symptom, Observation observation) {
    final MedicalRecord record = access.record();
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
    // record retrieved + available; step-2 reasoning attaches here
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
