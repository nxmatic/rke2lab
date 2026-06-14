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
  private final DriftSpecialist driftSpecialist;

  private Generalist(
      List<Specialist> specialists, ClinicalAccess access, DriftSpecialist driftSpecialist) {
    this.specialists = List.copyOf(specialists);
    this.access = access;
    this.driftSpecialist = driftSpecialist;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private List<Specialist> specialists = List.of();
    private ClinicalAccess access;
    private DriftSpecialist driftSpecialist;

    public Builder specialists(List<Specialist> specialists) {
      this.specialists = specialists;
      return this;
    }

    public Builder access(ClinicalAccess access) {
      this.access = access;
      return this;
    }

    public Builder driftSpecialist(DriftSpecialist driftSpecialist) {
      this.driftSpecialist = driftSpecialist;
      return this;
    }

    public Generalist build() {
      if (access == null) {
        throw new IllegalStateException("access is required");
      }
      // No ledger wired → the drift inference is computed and returned but not persisted, coherent
      // with the registry's no-backend degrade. The real writer is wired at the reconstruction
      // site.
      final DriftSpecialist drift =
          driftSpecialist != null ? driftSpecialist : new DriftSpecialist(intervention -> {});
      return new Generalist(specialists, access, drift);
    }
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
   * The patient consults: refer the symptom + observation to the routed specialists, collect each
   * specialist's {@link ReferralReply} (always an assessment, optionally a prescription), return a
   * remediation plan carrying the replies.
   */
  public RemediationPlan consult(Symptom symptom, Observation observation) {
    final MedicalRecord record = access.record();
    firstLook(record, symptom, observation);
    final List<Specialty> route = routeBySymptom(symptom);
    if (route.isEmpty()) {
      return new RemediationPlan(
          symptom, List.of(), "no specialist routes for symptom " + symptom.id());
    }

    final Referral referral = Referral.of(record.patient(), symptom, observation, record);
    final List<ReferralReply> replies = new ArrayList<>();
    for (Specialist specialist : specialists) {
      if (route.contains(specialist.domain())) {
        replies.add(specialist.diagnose(referral));
      }
    }

    final long prescribed = replies.stream().filter(ReferralReply::hasPrescription).count();
    final String summary =
        replies.isEmpty()
            ? "consulted " + route + " for " + symptom.id() + "; no specialist replied"
            : replies.size()
                + " reply(ies) for "
                + symptom.id()
                + " from "
                + route
                + "; "
                + prescribed
                + " prescription(s)";
    return new RemediationPlan(symptom, replies, summary);
  }

  /**
   * The follow-up coordination at synthesis: for each visit with a following visit, for each
   * Expectation on that visit whose predicate held at the next visit (the symptom resolved), review
   * the problem with the drift specialist and collect its letters. The ledger is loaded once by the
   * caller (the reconstruction wiring) and passed in — no no-ledger overload.
   *
   * <p>"Resolved-but-unadministered" today is simply "resolved": no engine administers fixes yet,
   * so every resolved expectation is reviewed.
   */
  public List<ReferralReply> reviewOpenProblems(MedicalRecord record, InterventionLedger ledger) {
    final List<ReferralReply> letters = new ArrayList<>();
    final List<Visit> visits = record.visits();
    for (int i = 0; i + 1 < visits.size(); i++) {
      final Visit visit = visits.get(i);
      final Visit nextVisit = visits.get(i + 1);
      for (Expectation expectation : visit.expectations()) {
        if (expectation.predicate().heldAt(nextVisit)) {
          final ProblemReview review =
              new ProblemReview(expectation.problem(), expectation, visit, nextVisit, ledger);
          letters.add(driftSpecialist.review(review));
        }
      }
    }
    return letters;
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
