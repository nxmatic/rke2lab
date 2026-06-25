package io.nxmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast, file-reading checks of {@link InterventionLedgerSource}. The deploying round-trip (which
 * drives a real Pulumi inline {@code up()} via the writer) lives in {@code @Tag("host")} {@link
 * InterventionLedgerRoundTripLiveTest}, so it is excluded from the default test run.
 */
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
}
