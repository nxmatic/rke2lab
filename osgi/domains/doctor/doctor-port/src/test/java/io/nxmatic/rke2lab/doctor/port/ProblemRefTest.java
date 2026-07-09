package io.nxmatic.rke2lab.doctor.port;

import static io.nxmatic.rke2lab.doctor.records.Checkpoint.CLUSTER_READINESS;
import static io.nxmatic.rke2lab.doctor.records.Checkpoint.SYSTEMD_ADAPTER;
import static io.nxmatic.rke2lab.doctor.records.Symptom.CONNECTION_REFUSED;
import static io.nxmatic.rke2lab.doctor.records.Symptom.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProblemRefTest {

  @Test
  void toRef_renders_checkpoint_and_symptom() {
    final ProblemRef ref = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    assertEquals("systemd-adapter/connection-refused", ref.toRef());
  }

  @Test
  void toRef_renders_checkpoint_only_when_no_symptom() {
    final ProblemRef ref = ProblemRef.of(SYSTEMD_ADAPTER);
    assertEquals("systemd-adapter", ref.toRef());
  }

  @Test
  void parse_round_trips_symptom_bearing_ref() {
    final Optional<ProblemRef> parsed = ProblemRef.parse("systemd-adapter/connection-refused");
    assertTrue(parsed.isPresent());
    assertEquals("systemd-adapter/connection-refused", parsed.get().toRef());
  }

  @Test
  void parse_round_trips_checkpoint_only_ref() {
    final Optional<ProblemRef> parsed = ProblemRef.parse("systemd-adapter");
    assertTrue(parsed.isPresent());
    assertEquals("systemd-adapter", parsed.get().toRef());
  }

  @Test
  void parse_returns_empty_for_blank() {
    assertTrue(ProblemRef.parse("").isEmpty());
  }

  @Test
  void parse_returns_empty_for_unknown_checkpoint() {
    assertTrue(ProblemRef.parse("nope").isEmpty());
  }

  @Test
  void parse_returns_empty_for_unknown_symptom() {
    assertTrue(ProblemRef.parse("systemd-adapter/not-a-symptom").isEmpty());
  }

  @Test
  void parse_returns_empty_for_trailing_slash() {
    assertTrue(ProblemRef.parse("systemd-adapter/").isEmpty());
  }

  @Test
  void checkpoint_only_ref_explains_any_symptom_at_same_checkpoint() {
    final ProblemRef checkpointOnly = ProblemRef.of(SYSTEMD_ADAPTER);
    final ProblemRef specificSymptom = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    assertTrue(checkpointOnly.explains(specificSymptom));
  }

  @Test
  void symptom_specific_ref_does_not_explain_different_symptom() {
    final ProblemRef connectionRefused = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    final ProblemRef timeout = ProblemRef.of(SYSTEMD_ADAPTER, TIMEOUT);
    assertFalse(connectionRefused.explains(timeout));
  }

  @Test
  void symptom_specific_ref_explains_itself() {
    final ProblemRef ref = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    assertTrue(ref.explains(ref));
  }

  @Test
  void cross_checkpoint_refs_never_explain() {
    final ProblemRef clusterRef = ProblemRef.of(CLUSTER_READINESS);
    final ProblemRef systemdRef = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    assertFalse(clusterRef.explains(systemdRef));
  }

  @Test
  void explainsSymptom_checkpoint_only_explains_any() {
    final ProblemRef checkpointOnly = ProblemRef.of(SYSTEMD_ADAPTER);
    assertTrue(checkpointOnly.explainsSymptom(TIMEOUT));
    assertTrue(checkpointOnly.explainsSymptom(CONNECTION_REFUSED));
  }

  @Test
  void explainsSymptom_specific_symptom_explains_only_itself() {
    final ProblemRef connectionRefused = ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED);
    assertTrue(connectionRefused.explainsSymptom(CONNECTION_REFUSED));
    assertFalse(connectionRefused.explainsSymptom(TIMEOUT));
  }

  @Test
  void null_checkpoint_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> ProblemRef.of(null, CONNECTION_REFUSED));
    assertThrows(IllegalArgumentException.class, () -> ProblemRef.of(null));
  }
}
