package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.pulumi.automation.PulumiBackendLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InterventionLedgerSourceTest {

  @Test
  void absentStackReturnsEmptyLedger(@TempDir Path backendDir) {
    final InterventionLedgerSource source = new InterventionLedgerSource(backendDir);
    final InterventionLedger ledger = source.load();

    assertTrue(ledger.interventions().isEmpty(), "absent stack should return empty ledger");
  }

  @Test
  void presentButUnreadableHistoryPropagates(@TempDir Path backendDir) throws Exception {
    // A present-but-malformed history is corruption, not absence: it must propagate, never fold to
    // an empty ledger (which would silently resurrect the false-efficacy bug this ledger kills).
    final Path historyDir =
        PulumiBackendLayout.historyDir(
            backendDir, InterventionLedgerLayout.PROJECT, InterventionLedgerLayout.STACK);
    Files.createDirectories(historyDir);
    Files.writeString(historyDir.resolve("dev-1780000000000000000.history.json"), "{ not json");

    final InterventionLedgerSource source = new InterventionLedgerSource(backendDir);
    assertThrows(RuntimeException.class, source::load);
  }

  @Test
  void roundTripsAppendedInterventions(@TempDir Path backendDir) throws Exception {
    // Append two interventions with different times via the writer
    final Instant t1 = Instant.parse("2026-06-14T09:30:00Z");
    final Instant t2 = Instant.parse("2026-06-14T09:31:00Z");

    final Intervention first =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t1,
            "first fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final Intervention second =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t2,
            "second fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(first);
    writer.append(second);

    // Read back via InterventionLedgerSource
    final InterventionLedgerSource source = new InterventionLedgerSource(backendDir);
    final InterventionLedger ledger = source.load();

    final List<Intervention> interventions = ledger.interventions();
    assertEquals(2, interventions.size(), "should recover both appended interventions");

    // Verify time-ordered (first then second)
    assertEquals(t1, interventions.get(0).when(), "first intervention by time");
    assertEquals("first fix", interventions.get(0).what());
    assertEquals(Provenance.OPERATOR_MANUAL, interventions.get(0).provenance());
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        interventions.get(0).problem());

    assertEquals(t2, interventions.get(1).when(), "second intervention by time");
    assertEquals("second fix", interventions.get(1).what());
    assertEquals(Provenance.OPERATOR_MANUAL, interventions.get(1).provenance());
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        interventions.get(1).problem());
  }
}
