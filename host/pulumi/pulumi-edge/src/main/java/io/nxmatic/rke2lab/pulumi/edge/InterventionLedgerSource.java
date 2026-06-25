package io.nxmatic.rke2lab.pulumi.edge;

import io.nxmatic.rke2lab.doctor.port.InterventionReader;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.StackCoordinate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the intervention-ledger stack back into an {@link InterventionLedger} by folding its
 * history — the twin of {@link MedicalRecordReader}, which folds one {@link Visit} per entry. Each
 * {@code append} wrote one history entry carrying one {@link InterventionResource} under {@link
 * InterventionLedgerLayout#OUTPUT_KEY}, so the sequence lives in history, not in distinct resource
 * names. (Same fold shape as {@code MedicalRecordReader} — two folders, not three, so no generic
 * {@code HistoryFold<T>} is extracted yet.)
 */
public final class InterventionLedgerSource {

  private final Path backendDir;
  private final StackCoordinate coordinate;

  public InterventionLedgerSource(Path backendDir, StackCoordinate coordinate) {
    this.backendDir = backendDir;
    this.coordinate = coordinate;
  }

  public InterventionLedgerSource(Path backendDir) {
    this(backendDir, InterventionLedgerLayout.ledger());
  }

  public InterventionLedger load() {
    final StackHandle handle =
        StackHandle.forBackend(backendDir, coordinate.project(), coordinate.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // Absence is already the empty path: an unwritten ledger leaves no history dir, so entries()
      // returns an empty list WITHOUT throwing. A StackException here therefore means the history
      // is
      // present but unreadable — corruption or an I/O fault. Masking it as an empty ledger would
      // silently resurrect the dishonesty this ledger exists to kill: efficacy would compute as if
      // no intervention ever happened. Propagate (matching the per-entry read below); never mask.
      throw new RuntimeException(
          "intervention ledger present but unreadable under " + backendDir, e);
    }

    final List<Intervention> interventions = new ArrayList<>();
    for (StackHistory.Entry entry : entries) {
      // A present entry that cannot be read is exceptional, not absence: let the StackException
      // propagate rather than masking corruption as an empty ledger (layered error contract).
      final StackSnapshot snapshot = snapshotOf(handle, entry);
      snapshot.outputsNamed(InterventionLedgerLayout.OUTPUT_KEY).stream()
          .map(InterventionReader::fromOutputMap)
          .flatMap(Optional::stream)
          .forEach(interventions::add);
    }
    return new InterventionLedger(interventions);
  }

  private static StackSnapshot snapshotOf(StackHandle handle, StackHistory.Entry entry) {
    try {
      return handle.snapshotOf(entry);
    } catch (StackException e) {
      // version is a weak id (the file backend leaves it 0 across a history), so name the entry by
      // its timestamp too — keeps the error actionable.
      throw new RuntimeException(
          "ledger entry present in history but unreadable: version="
              + entry.version()
              + " at "
              + entry.when(),
          e);
    }
  }
}
