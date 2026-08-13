package io.seedmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.doctor.contract.ClinicianId;
import io.seedmatic.rke2lab.doctor.internal.Generalist;
import io.seedmatic.rke2lab.doctor.spi.Specialist;
import io.seedmatic.rke2lab.doctor.testkit.FakeSpecialist;
import org.junit.jupiter.api.Test;

class ClinicianIdentityTest {

  @Test
  void a_specialist_declares_an_id_derived_from_its_specialty() {
    final Specialist dbus = new FakeSpecialist();
    // Default id = the specialty's lowercased name, kebab-friendly.
    assertEquals(new ClinicianId("systemd"), dbus.clinicianId());
  }

  @Test
  void the_generalist_declares_the_generalist_id() {
    assertEquals(new ClinicianId("generalist"), Generalist.GENERALIST_ID);
  }
}
