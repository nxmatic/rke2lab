package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.Patient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The full medical record of one {@link Patient}: its visits in chronological order. The four
 * clinical questions a doctor asks of a record — the chief complaint, a symptom's history, a
 * treatment's efficacy, and a symptom's comorbidity — are answered here as pure folds over the
 * visits, with no Pulumi, I/O, or mutation.
 */
public record MedicalRecord(Patient patient, List<Visit> visits) {

  public MedicalRecord {
    // Order by WHEN, not version: the file backend leaves the update version at 0 across a single
    // deployment's whole history, so time (startTime) is what actually orders the visits — and the
    // "next visit" the efficacy lookahead depends on must mean the next in time. Version stays a
    // tiebreaker for the rare same-instant case.
    visits =
        visits == null
            ? List.of()
            : visits.stream()
                .sorted(Comparator.comparing(Visit::when).thenComparingInt(Visit::version))
                .toList();
  }

  public ChiefComplaint chiefComplaint() {
    if (visits.isEmpty()) {
      return new ChiefComplaint(List.of());
    }
    return new ChiefComplaint(visits.get(visits.size() - 1).reports());
  }

  public SymptomHistory historyOf(Symptom symptom) {
    final List<SymptomHistory.Occurrence> occurrences =
        visits.stream()
            .filter(visit -> visit.symptomsRaised().contains(symptom))
            .map(
                visit ->
                    new SymptomHistory.Occurrence(
                        visit.version(), firstCheckpointRaising(visit, symptom)))
            .toList();
    return new SymptomHistory(symptom, occurrences);
  }

  public TreatmentEfficacy efficacyOf(Symptom symptom, InterventionLedger ledger) {
    final List<TreatmentEfficacy.Attempt> attempts = new ArrayList<>();
    for (int i = 0; i < visits.size(); i++) {
      final Visit visit = visits.get(i);
      // Per-symptom: the treatment must have been written for THIS symptom, not merely present
      // somewhere in the visit — otherwise another symptom's prescription would be credited here.
      final Optional<RemediationProgramRef> treatment = treatmentFor(visit, symptom);
      final boolean hasFollowing = i + 1 < visits.size();
      // Efficacy is only judgeable when a treatment was actually written for the symptom AND a
      // later encounter exists to observe the outcome; an untreated visit, or the last visit, tells
      // us nothing.
      if (treatment.isPresent() && hasFollowing) {
        final Visit next = visits.get(i + 1);
        final boolean recurred = next.symptomsRaised().contains(symptom);
        // Confounded: a non-engine intervention in the window (visit.when(), next.when()] explains
        // this symptom, so the resolution cannot be credited to the prescription. explainsSymptom
        // is checkpoint-agnostic — the symptom is the efficacy join key.
        final boolean confounded =
            ledger.between(visit.when(), next.when()).stream()
                .filter(it -> it.provenance() != Provenance.PULUMI_ENGINE)
                .anyMatch(it -> it.problem().explainsSymptom(symptom));
        attempts.add(
            new TreatmentEfficacy.Attempt(
                visit.version(), treatment.get().id(), recurred, confounded));
      }
    }
    return new TreatmentEfficacy(symptom, attempts);
  }

  // CAVEAT (provisional, revisit): when a symptom carries several prescriptions in one visit we
  // attribute the FIRST as the attempt. This is arbitrary — there is no weighting/priority model
  // behind "first", so it is NOT "the most important treatment", just the first written. Settle a
  // real ponderation rule (or emit one attempt per prescription) before efficacy drives any
  // decision. See the doctor design notes.
  private static Optional<RemediationProgramRef> treatmentFor(Visit visit, Symptom symptom) {
    return visit.reports().stream()
        .filter(report -> report.symptom() == symptom)
        .flatMap(report -> report.plan().prescriptions().stream())
        .map(Prescription::programRef)
        .findFirst();
  }

  public Comorbidity comorbiditiesWith(Symptom symptom) {
    final Set<Symptom> cooccurring = new LinkedHashSet<>();
    for (Visit visit : visits) {
      final Set<Symptom> raised = visit.symptomsRaised();
      if (raised.contains(symptom)) {
        raised.stream().filter(other -> other != symptom).forEach(cooccurring::add);
      }
    }
    return new Comorbidity(symptom, List.copyOf(cooccurring));
  }

  private static String firstCheckpointRaising(Visit visit, Symptom symptom) {
    return visit.reports().stream()
        .filter(report -> report.symptom() == symptom)
        .map(ConsultationReport::checkpointId)
        .findFirst()
        .orElseThrow();
  }
}
