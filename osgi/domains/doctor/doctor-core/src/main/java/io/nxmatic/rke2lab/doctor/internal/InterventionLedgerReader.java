package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Folds the host {@link io.nxmatic.rke2lab.doctor.port.InterventionJournal}'s opaque {@code
 * intervention} {@link Document}s into an {@link InterventionLedger} INSIDE the bundle realm, via
 * the moved {@link InterventionReader} — the twin of {@link MedicalRecordReader}. Each Document's
 * payload carries the raw {@code interventions} output blob list a ledger history entry registered;
 * this reader parses it with doctor-core's OWN jackson (no jackson type crosses the seam) and
 * rebuilds one {@link Intervention} per readable blob. A malformed entry degrades to skipping its
 * blobs (the additive, tolerant contract of {@link InterventionReader}); the ledger never throws.
 */
public final class InterventionLedgerReader {

  private static final TypeReference<Map<String, Object>> PAYLOAD_SHAPE = new TypeReference<>() {};

  private final ObjectMapper mapper = new ObjectMapper();

  public InterventionLedger read(List<Document> journal) {
    final List<Intervention> interventions = new ArrayList<>();
    for (Document entry : journal) {
      final Map<String, Object> payload;
      try {
        payload = mapper.readValue(entry.payload(), PAYLOAD_SHAPE);
      } catch (JsonProcessingException e) {
        // A malformed envelope is degraded, not fatal: the ledger folds the readable entries. The
        // host journal already propagated genuine stack corruption before producing a Document.
        continue;
      }
      blobsOf(payload.get(WorldGatewayCatalog.FIELD_INTERVENTIONS)).stream()
          .map(InterventionReader::fromOutputMap)
          .flatMap(Optional::stream)
          .forEach(interventions::add);
    }
    return new InterventionLedger(interventions);
  }

  private static List<?> blobsOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
