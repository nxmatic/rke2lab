package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.doctor.ClinicianId;
import io.nxmatic.rke2lab.doctor.Generalist;
import io.nxmatic.rke2lab.doctor.Specialist;
import org.junit.jupiter.api.Test;

class ClinicianIdentityTest {

  @Test
  void a_specialist_declares_an_id_derived_from_its_specialty() {
    final Specialist dbus =
        new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig());
    // Default id = the specialty's lowercased name, kebab-friendly.
    assertEquals(new ClinicianId("systemd"), dbus.clinicianId());
  }

  @Test
  void the_generalist_declares_the_generalist_id() {
    assertEquals(new ClinicianId("generalist"), Generalist.GENERALIST_ID);
  }
}
