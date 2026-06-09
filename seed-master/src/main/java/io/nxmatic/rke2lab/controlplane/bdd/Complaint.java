package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;

/**
 * What the patient brings to a single consultation: the reports it raised. An empty complaint is
 * the healthy case — the patient self-observed and had nothing to report.
 */
public record Complaint(List<ConsultationReport> reports) {

  public Complaint {
    reports = reports == null ? List.of() : List.copyOf(reports);
  }

  public boolean isEmpty() {
    return reports.isEmpty();
  }
}
