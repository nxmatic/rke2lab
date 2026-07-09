package io.nxmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionJournal;
import io.nxmatic.rke2lab.seed.broker.port.Coordinate;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Domain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Pulumi file-backend implementation of the host {@link InterventionJournal} READ port
 * (Layer-1): it walks the one fixed intervention-ledger stack's history and emits one {@code
 * intervention} {@link Document} PER intervention blob each entry registered, WITHOUT interpreting
 * the content. OSGi rebuilds the {@code InterventionLedger} from these Documents inside the bundle
 * realm — one Document decodes as one {@code InterventionWire}.
 *
 * <p>This is the Layer-1 half of what {@code InterventionLedgerSource.load} used to do host-side:
 * the history walk and the per-entry output harvest stay here (stack knowledge); the blob→ledger
 * fold moved OSGi-side ({@code InterventionReader}). A present-but-unreadable history propagates
 * (corruption is not absence) rather than masking the dishonesty the ledger exists to kill; an
 * unwritten ledger leaves no history and yields an empty list. A null backend yields no entries.
 */
public final class StackInterventionJournal implements InterventionJournal {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Optional<Path> backendDir;
  private final StackCoordinate coordinate;

  public StackInterventionJournal(Optional<Path> backendDir) {
    this.backendDir = backendDir;
    this.coordinate = InterventionLedgerLayout.ledger();
  }

  @Override
  public List<Document> entries() {
    if (backendDir.isEmpty()) {
      return List.of();
    }
    final Path backend = backendDir.get();
    final StackHandle handle =
        StackHandle.forBackend(backend, coordinate.project(), coordinate.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // An unwritten ledger leaves no history dir, so entries() returns empty WITHOUT throwing. A
      // StackException here means the history is present but unreadable — corruption or an I/O
      // fault. Masking it as empty would resurrect the dishonesty the ledger kills: efficacy would
      // compute as if no intervention ever happened. Propagate; never mask.
      throw new RuntimeException("intervention ledger present but unreadable under " + backend, e);
    }

    final List<Document> journal = new ArrayList<>(entries.size());
    for (StackHistory.Entry entry : entries) {
      // A present entry that cannot be read is exceptional, not absence: let the StackException
      // propagate rather than masking corruption as an empty ledger (layered error contract).
      journal.addAll(interventionDocuments(snapshotOf(handle, entry)));
    }
    return journal;
  }

  /**
   * One {@code intervention} Document PER intervention blob a history entry registered. {@code
   * StackSnapshot.outputsNamed} returns a list (it collects the output across the entry's
   * resources); each element is one intervention's flat map, which the host copies verbatim into a
   * Document payload — the wire shape OSGi decodes as an {@code InterventionWire}. The host never
   * parses a blob; it serializes each with its OWN jackson. (The former single Document wrapping
   * the whole {@code {interventions:[…]}} list was the array-output framing leaking into the
   * payload; unwrapping it here keeps one coordinate = one wire shape.)
   */
  private List<Document> interventionDocuments(StackSnapshot snapshot) {
    final List<Document> documents = new ArrayList<>();
    for (Object blob : snapshot.outputsNamed(InterventionLedgerLayout.OUTPUT_KEY)) {
      documents.add(
          new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(blob)));
    }
    return documents;
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

  private String serialize(Object blob) {
    try {
      return mapper.writeValueAsString(blob);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize intervention Document payload", e);
    }
  }
}
