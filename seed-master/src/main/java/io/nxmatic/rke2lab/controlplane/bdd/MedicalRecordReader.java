package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.StackException;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs a {@link Patient}'s {@link MedicalRecord} by folding the {@link SnapshotSource}
 * timeline into one {@link Visit} per readable entry. The aggregator does fail-AT-END, not
 * fail-fast: an unreadable entry is collected (identity-enriched) and the fold continues; if any
 * entry failed it throws a {@link MedicalRecordReconstructionException} carrying the partial record
 * plus one suppressed {@link MedicalRecordReconstructionException.EntryFailure} per failure,
 * leaving the policy decision to the caller. An empty timeline is nothing-here: an empty record, no
 * exception.
 */
final class MedicalRecordReader {

  private final SnapshotSource source;

  MedicalRecordReader(SnapshotSource source) {
    this.source = source;
  }

  MedicalRecord read(Patient patient) throws MedicalRecordReconstructionException {
    final List<StackHistory.Entry> timeline;
    try {
      timeline = source.timeline();
    } catch (StackException e) {
      // The spine is the precondition for any reconstruction: with no readable timeline there is
      // no partial to build, only the failure to report. The leaf already carries path() (the
      // history dir/file) as its identity — no version/when to enrich, so it is suppressed as-is.
      throw failed(patient, new MedicalRecord(patient, List.of()), List.of(e));
    }

    final List<Visit> visits = new ArrayList<>();
    final List<Throwable> failures = new ArrayList<>();

    for (StackHistory.Entry entry : timeline) {
      try {
        final StackSnapshot snapshot = source.at(entry);
        final List<ConsultationReport> reports =
            snapshot.outputsNamed(ConsultationReport.OUTPUT_KEY).stream()
                .map(ConsultationReportReader::fromOutputMap)
                .flatMap(Optional::stream)
                .toList();
        visits.add(new Visit(entry.version(), entry.when(), reports));
      } catch (StackException e) {
        // Identity-enrichment: a subordinate read failure does not decide policy; record WHICH
        // entry failed (the leaf carries only the file path) and keep folding.
        failures.add(new MedicalRecordReconstructionException.EntryFailure(entry, e));
      }
    }

    final MedicalRecord partial = new MedicalRecord(patient, visits);
    if (failures.isEmpty()) {
      return partial;
    }
    throw failed(patient, partial, failures);
  }

  private static MedicalRecordReconstructionException failed(
      Patient patient, MedicalRecord partial, List<? extends Throwable> failures) {
    final MedicalRecordReconstructionException aggregate =
        new MedicalRecordReconstructionException(partial, failures.size());
    failures.forEach(aggregate::addSuppressed);
    return aggregate;
  }
}
