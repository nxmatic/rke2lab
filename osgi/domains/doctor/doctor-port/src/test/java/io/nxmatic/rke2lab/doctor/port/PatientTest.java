package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.doctor.records.Patient;
import org.junit.jupiter.api.Test;

class PatientTest {

  @Test
  void qualifiedName_concatenatesOrgProjectStack() {
    Patient patient = new Patient("organization", "rke2lab", "dev");

    assertEquals("organization/rke2lab/dev", patient.qualifiedName());
  }
}
