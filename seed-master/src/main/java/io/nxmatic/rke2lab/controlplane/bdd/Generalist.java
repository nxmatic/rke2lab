package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.ArrayList;
import java.util.List;

/**
 * The doctor's coordinator. When a checkpoint fails, the patient consults: the Generalist takes the
 * symptom + the captured {@link Dossier}, and synthesizes a {@link RemediationPlan}.
 *
 * <ol>
 *   <li><b>firstLook</b> — a first-level read. If the dossier needs no specialist (e.g. an ok
 *       snapshot reached here by mistake, or a symptom with no routing), the generalist returns a
 *       plan directly without disturbing specialists.
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

  public Generalist(List<Specialist> specialists) {
    this.specialists = List.copyOf(specialists);
  }

  /** The patient consults: diagnose the symptom against the dossier, return a remediation plan. */
  public RemediationPlan consult(Symptom symptom, Dossier dossier) {
    final List<SpecialistDomain> route = routeBySymptom(symptom);
    if (route.isEmpty()) {
      return new RemediationPlan(
          symptom, List.of(), "no specialist routes for symptom " + symptom.id());
    }

    final List<Prescription> prescriptions = new ArrayList<>();
    for (Specialist specialist : specialists) {
      if (route.contains(specialist.domain())) {
        specialist.diagnose(symptom, dossier).ifPresent(prescriptions::add);
      }
    }

    final String summary =
        prescriptions.isEmpty()
            ? "consulted " + route + " for " + symptom.id() + "; no treatment offered"
            : prescriptions.size() + " prescription(s) for " + symptom.id() + " from " + route;
    return new RemediationPlan(symptom, prescriptions, summary);
  }

  /**
   * Deterministic symptom → domain routing. A readable rules table, not inference: a symptom maps
   * to the domains whose specialists could treat it. Unknown symptoms route nowhere (empty plan).
   */
  private static List<SpecialistDomain> routeBySymptom(Symptom symptom) {
    return switch (symptom) {
      case CONNECTION_REFUSED -> List.of(SpecialistDomain.SYSTEMD, SpecialistDomain.NETWORK);
      case TIMEOUT -> List.of(SpecialistDomain.NETWORK);
      // Cluster-readiness symptoms are typed and named in the runbook from Increment D; no
      // specialist treats them yet, so they route to the CLUSTER domain and yield an empty plan
      // (symptom seen, no treatment offered) until a cluster specialist is added.
      case KUBECONFIG_MISSING, CONTROLLER_NOT_READY -> List.of(SpecialistDomain.CLUSTER);
      case API_NOT_READY -> List.of(SpecialistDomain.CLUSTER, SpecialistDomain.NETWORK);
    };
  }
}
