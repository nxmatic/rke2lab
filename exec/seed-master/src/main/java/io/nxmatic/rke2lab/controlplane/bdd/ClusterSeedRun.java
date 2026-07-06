package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.report.model.ReportModel;
import java.util.Map;

/** The synchronous harvest of one seed run: the narration (runbook) and the Pulumi outputs. */
public record ClusterSeedRun(ReportModel runbook, Map<String, Object> outputs) {}
