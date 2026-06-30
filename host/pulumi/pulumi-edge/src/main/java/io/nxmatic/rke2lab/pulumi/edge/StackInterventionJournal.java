package io.nxmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionJournal;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pulumi file-backend implementation of the host {@link InterventionJournal} READ port
 * (Layer-1): it walks the one fixed intervention-ledger stack's history and wraps each entry's RAW
 * {@code interventions} output blob into one opaque {@code intervention} {@link Document}, WITHOUT
 * interpreting the content. OSGi rebuilds the {@code InterventionLedger} from these blobs inside
 * the bundle realm.
 *
 * <p>This is the Layer-1 half of what {@code InterventionLedgerSource.load} used to do host-side:
 * the history walk and the per-entry output harvest stay here (stack knowledge); the blob→ledger
 * fold moved OSGi-side ({@code InterventionReader}). A present-but-unreadable history propagates
 * (corruption is not absence) rather than masking the dishonesty the ledger exists to kill; an
 * unwritten ledger leaves no history and yields an empty list. A null backend yields no entries.
 */
public final class StackInterventionJournal implements InterventionJournal {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Path backendDir;
  private final StackCoordinate coordinate;

  public StackInterventionJournal(Path backendDir) {
    this.backendDir = backendDir;
    this.coordinate = InterventionLedgerLayout.ledger();
  }

  @Override
  public List<Document> entries() {
    if (backendDir == null) {
      return List.of();
    }
    final StackHandle handle =
        StackHandle.forBackend(backendDir, coordinate.project(), coordinate.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // An unwritten ledger leaves no history dir, so entries() returns empty WITHOUT throwing. A
      // StackException here means the history is present but unreadable — corruption or an I/O
      // fault. Masking it as empty would resurrect the dishonesty the ledger kills: efficacy would
      // compute as if no intervention ever happened. Propagate; never mask.
      throw new RuntimeException(
          "intervention ledger present but unreadable under " + backendDir, e);
    }

    final List<Document> journal = new ArrayList<>(entries.size());
    for (StackHistory.Entry entry : entries) {
      // A present entry that cannot be read is exceptional, not absence: let the StackException
      // propagate rather than masking corruption as an empty ledger (layered error contract).
      journal.add(interventionDocument(snapshotOf(handle, entry)));
    }
    return journal;
  }

  /**
   * The opaque {@code intervention} Document for one history entry: the raw {@code interventions}
   * output blob(s) harvested by {@code StackSnapshot.outputsNamed} — the SAME traversal that fed
   * the old {@code InterventionReader}. The host never parses the blobs; it serializes the envelope
   * with its OWN jackson.
   */
  private Document interventionDocument(StackSnapshot snapshot) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(
        InterventionLedgerLayout.OUTPUT_KEY,
        snapshot.outputsNamed(InterventionLedgerLayout.OUTPUT_KEY));
    return new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(payload));
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

  private String serialize(Map<String, Object> payload) {
    try {
      return mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize intervention Document payload", e);
    }
  }
}
