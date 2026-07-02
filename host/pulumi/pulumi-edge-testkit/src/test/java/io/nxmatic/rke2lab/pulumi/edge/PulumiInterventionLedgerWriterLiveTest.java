package io.nxmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.pulumi.edge.testkit.GrpcChannelNoiseCapture;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both cases drive a real Pulumi inline {@code up()} via the writer, so this class is tagged {@code
 * host} + {@code live} (excluded from the default run) and registers {@link
 * GrpcChannelNoiseCapture} for the benign gRPC channel noise the inline deployment leaves behind.
 *
 * <p>The host can no longer parse interventions into typed records (that fold moved OSGi-internal),
 * so the read-back asserts on the opaque {@code intervention} {@link Document}s the host READ port
 * ({@link StackInterventionJournal}) yields: one Document per history entry, carrying the raw
 * {@code interventions} output blob in its JSON {@code payload()}. Reconstruction of typed {@code
 * Intervention} records is proven OSGi-side (doctor-core in-container tests) and by the
 * full-reactor build.
 */
@Tag("host")
@Tag("live")
final class PulumiInterventionLedgerWriterLiveTest {

  @RegisterExtension
  static final GrpcChannelNoiseCapture GRPC_NOISE = new GrpcChannelNoiseCapture();

  @Test
  void appendInterventionToLedgerStack(@TempDir Path backendDir) throws Exception {
    // Append the canonical intervention Document the write seam carries (does an out-of-run up()).
    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(canonical("nft delete ..."));

    // Read back via the host READ journal: one opaque intervention Document, no typed fold.
    final List<Document> entries = new StackInterventionJournal(Optional.of(backendDir)).entries();

    assertEquals(1, entries.size(), "Should have exactly one intervention Document");

    final Document document = entries.get(0);
    assertEquals(
        Coordinate.INTERVENTION.slug(),
        document.coordinate(),
        "Document carries the canonical intervention coordinate");

    final String payload = document.payload();
    assertTrue(
        payload.contains("nft delete ..."), "payload carries the raw 'what' text: " + payload);
    assertTrue(
        payload.contains("operator-manual"), "payload carries the raw provenance id: " + payload);
    assertTrue(
        payload.contains("systemd-adapter/connection-refused"),
        "payload carries the raw problem ref: " + payload);
  }

  /**
   * The accumulation contract: two appends produce TWO history entries, each carrying its own
   * intervention — even though both share the one stable resource name. Accumulation is the history
   * fold, not many resources in one snapshot. Same-instant + same-provenance interventions must
   * BOTH survive (no resource-name collision loss). The host walks them as two opaque Documents;
   * the blob→ledger fold happens OSGi-side.
   */
  @Test
  void twoAppendsProduceTwoRecoverableHistoryEntries(@TempDir Path backendDir) throws Exception {
    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(canonical("first fix"));
    writer.append(canonical("second fix"));

    final List<Document> entries = new StackInterventionJournal(Optional.of(backendDir)).entries();
    assertEquals(2, entries.size(), "two appends must write two history entries");

    final List<String> payloads = entries.stream().map(Document::payload).toList();
    assertTrue(
        payloads.stream().anyMatch(p -> p.contains("first fix")),
        "the first append must survive in history");
    assertTrue(
        payloads.stream().anyMatch(p -> p.contains("second fix")),
        "the second append must survive in history");
  }

  /**
   * Build the canonical {@code intervention} Document the write seam carries: a flat JSON payload
   * (the {@code Intervention.toOutputMap} wire shape, owned OSGi-side) wrapped in the neutral
   * envelope. The host holds no doctor record types, so it assembles the wire fields directly.
   */
  private static Document canonical(String what) {
    final Map<String, Object> outputMap = new LinkedHashMap<>();
    outputMap.put("provenance", "operator-manual");
    outputMap.put("when", "2026-06-14T09:30:00Z");
    outputMap.put("what", what);
    outputMap.put("problem", Checkpoint.SYSTEMD_ADAPTER.slug() + "/" + "connection-refused");
    try {
      return new Document(
          Domain.DOCTOR.slug(),
          Coordinate.INTERVENTION.slug(),
          new ObjectMapper().writeValueAsString(outputMap));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
