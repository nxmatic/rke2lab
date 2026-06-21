package io.nxmatic.rke2lab.doctor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One encounter at a given stack version: the consultation reports the patient produced in that run
 * ({@code when} it happened, the {@code version} it belongs to) plus the {@link Expectation}s any
 * prescription recorded about the next visit. The medical record is a sequence of visits; the query
 * views below read across them, and a later drift check compares a visit's expectations against the
 * following one.
 */
public record Visit(
    int version, Instant when, List<ConsultationReport> reports, List<Expectation> expectations) {

  public Visit {
    reports = reports == null ? List.of() : List.copyOf(reports);
    expectations = expectations == null ? List.of() : List.copyOf(expectations);
  }

  public Set<Symptom> symptomsRaised() {
    final Set<Symptom> symptoms = new LinkedHashSet<>();
    for (ConsultationReport report : reports) {
      symptoms.add(report.symptom());
    }
    return symptoms;
  }

  public List<RemediationProgramRef> prescriptions() {
    return reports.stream()
        .flatMap(report -> report.plan().prescriptions().stream())
        .map(Prescription::programRef)
        .toList();
  }
}
