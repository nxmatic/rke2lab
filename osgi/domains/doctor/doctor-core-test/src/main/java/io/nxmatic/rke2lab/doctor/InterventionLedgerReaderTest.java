package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the envelope-unwrapping layer of {@link InterventionLedgerReader} that the well-tested fold
 * {@link InterventionReader#fromOutputMap} sits inside: {@code payload → FIELD_INTERVENTIONS list →
 * blobs → fromOutputMap}. Each Document is the SAME envelope {@code StackInterventionJournal}
 * produces — {@code {"interventions": [<Intervention.toOutputMap blob>, ...]}} serialized, in an
 * {@code intervention} {@link Document}. The reader degrades a missing / null / non-list {@code
 * interventions} field to no contribution (never throws), and skips a malformed blob via the fold's
 * {@link java.util.Optional#empty()} while keeping the readable rest. The ledger time-orders by
 * {@code when}, so order assertions are over the {@code when} sort, mirroring the record reader.
 */
class InterventionLedgerReaderTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void read_twoInterventionDocuments_returnsLedgerWithBothInTimeOrderFieldsIntact() {
    final Intervention earlier =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T10:13:50Z"),
            "nft delete rule",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            java.util.Optional.empty(),
            Map.of());
    final Intervention later =
        new Intervention(
            Provenance.PULUMI_ENGINE,
            Instant.parse("2026-06-13T11:42:00Z"),
            "systemctl restart rke2-server",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.TIMEOUT),
            java.util.Optional.of(RemediationProgramRef.RESTART_UNIT),
            Map.of("unitName", "rke2-server"));

    // Two separate ledger history entries, each its own Document — the journal walks history and
    // wraps one Document per entry, so the reader folds ACROSS Documents.
    final InterventionLedger ledger =
        new InterventionLedgerReader()
            .read(List.of(interventionDocument(earlier), interventionDocument(later)));

    assertEquals(2, ledger.interventions().size(), "both interventions must survive the fold");

    // The ledger time-orders by `when`: the earlier intervention comes first regardless of Document
    // arrival order. Each canonical field round-trips through the blob.
    final Intervention first = ledger.interventions().get(0);
    assertEquals(Provenance.OPERATOR_MANUAL, first.provenance(), "first provenance round-trips");
    assertEquals(Instant.parse("2026-06-13T10:13:50Z"), first.when(), "first when round-trips");
    assertEquals("nft delete rule", first.what(), "first what round-trips");
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        first.problem(),
        "first problem round-trips");

    final Intervention second = ledger.interventions().get(1);
    assertEquals(Provenance.PULUMI_ENGINE, second.provenance(), "second provenance round-trips");
    assertEquals(Instant.parse("2026-06-13T11:42:00Z"), second.when(), "second when round-trips");
    assertEquals("systemctl restart rke2-server", second.what(), "second what round-trips");
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.TIMEOUT),
        second.problem(),
        "second problem round-trips");
    assertEquals(
        java.util.Optional.of(RemediationProgramRef.RESTART_UNIT),
        second.prescriptionRef(),
        "second prescriptionRef round-trips");
    assertEquals("rke2-server", second.details().get("unitName"), "second details round-trip");
  }

  @Test
  void read_documentWhoseInterventionsFieldIsAbsent_contributesNothingWithoutThrowing() {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(WorldGatewayCatalog.FIELD_VERSION, 1);
    final Document noInterventionsField =
        new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(payload));

    final InterventionLedger ledger =
        new InterventionLedgerReader().read(List.of(noInterventionsField));

    assertTrue(
        ledger.interventions().isEmpty(), "an absent interventions field contributes nothing");
  }

  @Test
  void read_documentWhoseInterventionsFieldIsNull_contributesNothingWithoutThrowing() {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(WorldGatewayCatalog.FIELD_INTERVENTIONS, null);
    final Document nullInterventions =
        new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(payload));

    final InterventionLedger ledger =
        new InterventionLedgerReader().read(List.of(nullInterventions));

    assertTrue(ledger.interventions().isEmpty(), "a null interventions field contributes nothing");
  }

  @Test
  void read_documentWhoseInterventionsFieldIsNotAList_contributesNothingWithoutThrowing() {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(WorldGatewayCatalog.FIELD_INTERVENTIONS, "not-a-list");
    final Document scalarInterventions =
        new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(payload));

    final InterventionLedger ledger =
        new InterventionLedgerReader().read(List.of(scalarInterventions));

    assertTrue(
        ledger.interventions().isEmpty(), "a non-list interventions field degrades to nothing");
  }

  @Test
  void read_emptyJournal_returnsEmptyLedger() {
    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of());

    assertEquals(
        InterventionLedger.empty(), ledger, "an empty journal yields the empty ledger, no throw");
  }

  @Test
  void read_malformedBlobAmongGoodOnes_skipsItAndKeepsTheRest() {
    final Intervention good =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T10:13:50Z"),
            "nft delete rule",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            java.util.Optional.empty(),
            Map.of());

    // A blob missing the required `provenance` key — the fold yields Optional.empty() for it, so it
    // is skipped; its sibling blob in the SAME Document still reads.
    final Map<String, Object> malformedBlob =
        Map.of("when", "2026-06-13T11:00:00Z", "what", "orphaned", "problem", "systemd-adapter");
    final Document mixed = interventionDocumentOf(List.of(good.toOutputMap(), malformedBlob));

    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of(mixed));

    assertEquals(1, ledger.interventions().size(), "only the readable blob survives the fold");
    final Intervention only = ledger.interventions().get(0);
    assertEquals(Provenance.OPERATOR_MANUAL, only.provenance(), "the good blob's provenance reads");
    assertEquals("nft delete rule", only.what(), "the good blob's what reads");
  }

  /**
   * The opaque {@code intervention} Document for a single intervention, mirroring {@code
   * StackInterventionJournal.interventionDocument}: the blob list lives under {@code
   * FIELD_INTERVENTIONS} in the payload.
   */
  private static Document interventionDocument(Intervention intervention) {
    return interventionDocumentOf(List.of(intervention.toOutputMap()));
  }

  private static Document interventionDocumentOf(List<?> blobs) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(WorldGatewayCatalog.FIELD_INTERVENTIONS, blobs);
    return new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), serialize(payload));
  }

  private static String serialize(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
