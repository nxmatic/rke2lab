package io.nxmatic.rke2lab.doctor.records;

import java.util.List;

/**
 * What the patient brings to a single consultation: the reports it raised. An empty complaint is
 * the healthy case — the patient self-observed and had nothing to report.
 */
public record ChiefComplaint(List<ConsultationReport> reports) {

  public ChiefComplaint {
    reports = reports == null ? List.of() : List.copyOf(reports);
  }

  public boolean isEmpty() {
    return reports.isEmpty();
  }
}
