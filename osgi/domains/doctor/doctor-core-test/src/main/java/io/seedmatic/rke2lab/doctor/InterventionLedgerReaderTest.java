package io.seedmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.Intervention;
import io.seedmatic.rke2lab.doctor.contract.InterventionLedger;
import io.seedmatic.rke2lab.doctor.contract.InterventionWire;
import io.seedmatic.rke2lab.doctor.contract.ProblemRef;
import io.seedmatic.rke2lab.doctor.contract.Provenance;
import io.seedmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.internal.InterventionLedgerReader;
import io.seedmatic.rke2lab.doctor.internal.InterventionReader;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the fold {@link InterventionLedgerReader} performs over the host journal's {@code
 * intervention} {@link SeedEnvelope}s: each SeedEnvelope carries ONE {@link InterventionWire} (one
 * ledger history entry = one intervention), which the reader decodes with the realm codec and
 * passes to {@link InterventionReader#fromWire}. A malformed SeedEnvelope (undecodable, or a wire
 * whose required refs do not parse) degrades to no contribution — never throws — while the readable
 * rest survives. The ledger time-orders by {@code when}, so order assertions are over that sort.
 */
class InterventionLedgerReaderTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void read_twoInterventionDocuments_returnsLedgerWithBothInTimeOrderFieldsIntact() {
    final SeedEnvelope earlier =
        interventionDocument(
            new InterventionWire(
                "operator-manual",
                Instant.parse("2026-06-13T10:13:50Z"),
                "nft delete rule",
                "systemd-adapter/connection-refused",
                Optional.empty(),
                Map.of()));
    final SeedEnvelope later =
        interventionDocument(
            new InterventionWire(
                "pulumi-engine",
                Instant.parse("2026-06-13T11:42:00Z"),
                "systemctl restart rke2-server",
                "systemd-adapter/timeout",
                Optional.of("restart-systemd-unit"),
                Map.of("unitName", "rke2-server")));

    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of(earlier, later));

    assertEquals(2, ledger.interventions().size(), "both interventions must survive the fold");

    // The ledger time-orders by `when`: the earlier intervention comes first regardless of arrival.
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
        Optional.of(RemediationProgramRef.RESTART_UNIT),
        second.prescriptionRef(),
        "second prescriptionRef round-trips");
    assertEquals("rke2-server", second.details().get("unitName"), "second details round-trip");
  }

  @Test
  void read_emptyJournal_returnsEmptyLedger() {
    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of());
    assertEquals(
        InterventionLedger.empty(), ledger, "an empty journal yields the empty ledger, no throw");
  }

  @Test
  void read_documentWithUnparseableWire_contributesNothingWithoutThrowing() {
    // A wire whose required `provenance` ref does not parse — fromWire yields empty, so the entry
    // is
    // skipped; a sibling readable SeedEnvelope still reads.
    final SeedEnvelope malformed =
        interventionDocument(
            new InterventionWire(
                "not-a-provenance",
                Instant.parse("2026-06-13T11:00:00Z"),
                "orphaned",
                "systemd-adapter/timeout",
                Optional.empty(),
                Map.of()));
    final SeedEnvelope good =
        interventionDocument(
            new InterventionWire(
                "operator-manual",
                Instant.parse("2026-06-13T10:13:50Z"),
                "nft delete rule",
                "systemd-adapter/connection-refused",
                Optional.empty(),
                Map.of()));

    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of(malformed, good));

    assertEquals(1, ledger.interventions().size(), "only the readable entry survives the fold");
    assertEquals(Provenance.OPERATOR_MANUAL, ledger.interventions().get(0).provenance());
  }

  @Test
  void read_documentWithMalformedPayload_contributesNothingWithoutThrowing() {
    final SeedEnvelope garbage =
        new SeedEnvelope("doctor", DoctorCoordinate.INTERVENTION.slug(), "not-json");
    final InterventionLedger ledger = new InterventionLedgerReader().read(List.of(garbage));
    assertTrue(ledger.interventions().isEmpty(), "an undecodable payload contributes nothing");
  }

  private static SeedEnvelope interventionDocument(InterventionWire wire) {
    return new SeedEnvelope("doctor", DoctorCoordinate.INTERVENTION.slug(), CODEC.encode(wire));
  }
}
