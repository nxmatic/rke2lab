package io.seedmatic.rke2lab.doctor.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PatientTest {

  @Test
  void qualifiedName_concatenatesOrgProjectStack() {
    Patient patient = new Patient("organization", "rke2lab", "dev");

    assertEquals("organization/rke2lab/dev", patient.qualifiedName());
  }
}
