package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.pulumi.automation.StackHandle;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PulumiInterventionLedgerWriterTest {

  @Test
  void appendInterventionToLedgerStack(@TempDir Path backendDir) throws Exception {
    // Build an intervention
    final Intervention it1 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-14T09:30:00Z"),
            "nft delete ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    // Append it (does an out-of-run up())
    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(it1);

    // Read back via StackHandle
    final StackHandle handle =
        StackHandle.forBackend(
            backendDir, InterventionLedgerLayout.PROJECT, InterventionLedgerLayout.STACK);

    final Optional<StackSnapshot> snapshotOpt = handle.currentSnapshot();
    assertTrue(snapshotOpt.isPresent(), "Stack should have a snapshot after append");

    final StackSnapshot snapshot = snapshotOpt.get();
    final List<Object> interventions = snapshot.outputsNamed(InterventionLedgerLayout.OUTPUT_KEY);

    assertEquals(1, interventions.size(), "Should have exactly one intervention");

    final Object raw = interventions.get(0);
    assertNotNull(raw, "Intervention output should not be null");

    final Optional<Intervention> roundTripped = InterventionReader.fromOutputMap(raw);
    assertTrue(roundTripped.isPresent(), "Intervention should parse from output map");

    final Intervention it1Back = roundTripped.get();
    assertEquals(Provenance.OPERATOR_MANUAL, it1Back.provenance());
    assertEquals(Instant.parse("2026-06-14T09:30:00Z"), it1Back.when());
    assertEquals("nft delete ...", it1Back.what());
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED), it1Back.problem());
    assertFalse(it1Back.prescriptionRef().isPresent());
    assertEquals(Map.of(), it1Back.details());
  }

  /**
   * The accumulation contract: two appends produce TWO history entries, each carrying its own
   * intervention — even though both share the one stable resource name. Accumulation is the history
   * fold, not many resources in one snapshot; this is what {@link InterventionLedgerSource} (Task
   * 7) folds. Same-instant + same-provenance interventions must BOTH survive (no resource-name
   * collision loss).
   */
  @Test
  void twoAppendsProduceTwoRecoverableHistoryEntries(@TempDir Path backendDir) throws Exception {
    final Instant sameInstant = Instant.parse("2026-06-14T09:30:00Z");
    final Intervention it1 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            sameInstant,
            "first fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention it2 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            sameInstant,
            "second fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(it1);
    writer.append(it2);

    final StackHandle handle =
        StackHandle.forBackend(
            backendDir, InterventionLedgerLayout.PROJECT, InterventionLedgerLayout.STACK);

    final List<StackHistory.Entry> entries = handle.history().entries();
    assertEquals(2, entries.size(), "two appends must write two history entries");

    // Fold each entry to the intervention it carries — the read model Task 7 implements.
    final List<String> whats =
        entries.stream()
            .map(
                entry -> {
                  try {
                    final StackSnapshot snapshot = handle.snapshotOf(entry);
                    final Object raw =
                        snapshot.outputsNamed(InterventionLedgerLayout.OUTPUT_KEY).get(0);
                    return InterventionReader.fromOutputMap(raw).orElseThrow().what();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .toList();

    assertTrue(whats.contains("first fix"), "the first append must survive in history");
    assertTrue(whats.contains("second fix"), "the second append must survive in history");
  }
}
