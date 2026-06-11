package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ClinicianIdTest {

  @Test
  void holds_a_kebab_id() {
    assertEquals("generalist", new ClinicianId("generalist").value());
  }

  @Test
  void rejects_a_null_or_blank_id() {
    assertThrows(IllegalArgumentException.class, () -> new ClinicianId(null));
    assertThrows(IllegalArgumentException.class, () -> new ClinicianId("  "));
  }

  @Test
  void equals_by_value() {
    assertEquals(new ClinicianId("network"), new ClinicianId("network"));
  }
}
