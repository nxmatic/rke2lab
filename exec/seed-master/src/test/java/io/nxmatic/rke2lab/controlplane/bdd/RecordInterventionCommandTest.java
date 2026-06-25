package io.nxmatic.rke2lab.controlplane.bdd;

import static io.nxmatic.rke2lab.doctor.records.Checkpoint.SYSTEMD_ADAPTER;
import static io.nxmatic.rke2lab.doctor.records.Symptom.CONNECTION_REFUSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordInterventionCommandTest {

  private static final class CapturingWriter implements InterventionLedgerWriter {
    final List<Intervention> appended = new ArrayList<>();

    @Override
    public void append(Intervention intervention) {
      appended.add(intervention);
    }
  }

  @Test
  void recordsOperatorManualInterventionByDefault() {
    final CapturingWriter writer = new CapturingWriter();
    final Instant now = Instant.parse("2026-06-14T09:30:00Z");

    final Intervention recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem", "systemd-adapter/connection-refused", "--what", "nft delete ..."
            },
            now,
            writer);

    assertEquals(Provenance.OPERATOR_MANUAL, recorded.provenance());
    assertEquals(now, recorded.when());
    assertEquals("nft delete ...", recorded.what());
    assertEquals(ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED), recorded.problem());
    assertTrue(recorded.prescriptionRef().isEmpty());
    assertEquals(List.of(recorded), writer.appended);
  }

  @Test
  void explicitWhenOverridesInjectedNow() {
    final CapturingWriter writer = new CapturingWriter();
    final Instant injectedNow = Instant.parse("2026-06-14T09:30:00Z");

    final Intervention recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem",
              "systemd-adapter/connection-refused",
              "--what",
              "nft delete ...",
              "--when",
              "2026-06-10T00:00:00Z"
            },
            injectedNow,
            writer);

    assertEquals(Instant.parse("2026-06-10T00:00:00Z"), recorded.when());
  }

  @Test
  void parsesPrescriptionRefWhenPresent() {
    final CapturingWriter writer = new CapturingWriter();

    final Intervention recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem",
              "systemd-adapter/connection-refused",
              "--what",
              "restarted the unit",
              "--prescription-ref",
              "restart-systemd-unit"
            },
            Instant.parse("2026-06-14T09:30:00Z"),
            writer);

    assertEquals(Optional.of(RemediationProgramRef.RESTART_UNIT), recorded.prescriptionRef());
  }

  @Test
  void missingProblemIsRejected() {
    final CapturingWriter writer = new CapturingWriter();

    final IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecordInterventionCommand.record(
                    new String[] {"--what", "nft delete ..."},
                    Instant.parse("2026-06-14T09:30:00Z"),
                    writer));

    assertTrue(
        ex.getMessage().toLowerCase(Locale.ROOT).contains("problem"),
        () -> "message should mention problem: " + ex.getMessage());
  }

  @Test
  void missingWhatIsRejected() {
    final CapturingWriter writer = new CapturingWriter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordInterventionCommand.record(
                new String[] {"--problem", "systemd-adapter/connection-refused"},
                Instant.parse("2026-06-14T09:30:00Z"),
                writer));
  }

  @Test
  void unknownProblemRefIsRejected() {
    final CapturingWriter writer = new CapturingWriter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordInterventionCommand.record(
                new String[] {"--problem", "no-such-checkpoint/whatever", "--what", "something"},
                Instant.parse("2026-06-14T09:30:00Z"),
                writer));
  }

  @Test
  void malformedWhenIsRejectedAsUsageError() {
    // A bad --when must surface as IllegalArgumentException (the uniform usage-error contract main
    // catches), not a raw DateTimeParseException that escapes the catch.
    final CapturingWriter writer = new CapturingWriter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordInterventionCommand.record(
                new String[] {
                  "--problem", "systemd-adapter/connection-refused",
                  "--what", "nft delete ...",
                  "--when", "not-a-timestamp"
                },
                Instant.parse("2026-06-14T09:30:00Z"),
                writer));
  }
}
