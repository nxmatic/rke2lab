package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Checkpoint;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterventionLedgerTest {

  @Test
  void canonical_constructor_sorts_by_when_ascending() {
    final Instant t1 = Instant.parse("2026-06-13T10:00:00Z");
    final Instant t2 = Instant.parse("2026-06-13T10:05:00Z");
    final Instant t3 = Instant.parse("2026-06-13T10:10:00Z");

    final Intervention i1 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t1,
            "fix1",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i2 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t2,
            "fix2",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i3 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t3,
            "fix3",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    // Pass OUT of order: [t3, t1, t2]
    final InterventionLedger ledger = new InterventionLedger(List.of(i3, i1, i2));

    // Assert returns time-sorted [t1, t2, t3]
    final List<Intervention> sorted = ledger.interventions();
    assertEquals(3, sorted.size());
    assertEquals(t1, sorted.get(0).when());
    assertEquals(t2, sorted.get(1).when());
    assertEquals(t3, sorted.get(2).when());
  }

  @Test
  void between_returns_interventions_in_window_exclusive_low_inclusive_high() {
    final Instant t1 = Instant.parse("2026-06-13T10:00:00Z");
    final Instant t2 = Instant.parse("2026-06-13T10:05:00Z");
    final Instant t3 = Instant.parse("2026-06-13T10:10:00Z");

    final Intervention i1 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t1,
            "fix1",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i2 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t2,
            "fix2",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i3 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t3,
            "fix3",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final InterventionLedger ledger = new InterventionLedger(List.of(i1, i2, i3));

    // between(t1, t3): t1 excluded, t3 included → returns [i2, i3]
    final List<Intervention> window = ledger.between(t1, t3);
    assertEquals(2, window.size());
    assertEquals(t2, window.get(0).when());
    assertEquals(t3, window.get(1).when());
  }

  @Test
  void between_single_intervention_in_window() {
    final Instant t1 = Instant.parse("2026-06-13T10:00:00Z");
    final Instant t2 = Instant.parse("2026-06-13T10:05:00Z");
    final Instant t3 = Instant.parse("2026-06-13T10:10:00Z");

    final Intervention i1 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t1,
            "fix1",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i2 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t2,
            "fix2",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());
    final Intervention i3 =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t3,
            "fix3",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final InterventionLedger ledger = new InterventionLedger(List.of(i1, i2, i3));

    // between(t1, t2): t1 excluded, t2 included → returns [i2]
    final List<Intervention> window = ledger.between(t1, t2);
    assertEquals(1, window.size());
    assertEquals(t2, window.get(0).when());
  }

  @Test
  void empty_ledger_between_returns_empty() {
    final Instant t1 = Instant.parse("2026-06-13T10:00:00Z");
    final Instant t3 = Instant.parse("2026-06-13T10:10:00Z");

    assertTrue(InterventionLedger.empty().between(t1, t3).isEmpty());
  }

  @Test
  void null_interventions_normalizes_to_empty() {
    final InterventionLedger ledger = new InterventionLedger(null);
    assertTrue(ledger.interventions().isEmpty());
  }
}
