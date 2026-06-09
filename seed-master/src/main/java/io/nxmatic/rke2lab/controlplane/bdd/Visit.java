package io.nxmatic.rke2lab.controlplane.bdd;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One encounter at a given stack version: the consultation reports the patient produced in that run
 * ({@code when} it happened, the {@code version} it belongs to). The medical record is a sequence
 * of visits; the query views below read across them.
 */
public record Visit(int version, Instant when, List<ConsultationReport> reports) {

  public Visit {
    reports = reports == null ? List.of() : List.copyOf(reports);
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
