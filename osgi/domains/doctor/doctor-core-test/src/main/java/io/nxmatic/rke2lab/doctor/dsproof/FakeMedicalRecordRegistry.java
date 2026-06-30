package io.nxmatic.rke2lab.doctor.dsproof;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The DS boot proof's EHR port: an empty-record {@link MedicalRecordRegistry} {@code @Component} so
 * {@code DefaultHealthSystem}'s {@code @Reference} to the registry is satisfied and the institution
 * activates. Returns an empty record for any patient — enough to admit and consult.
 */
@Component(service = MedicalRecordRegistry.class)
public final class FakeMedicalRecordRegistry implements MedicalRecordRegistry {

  @Override
  public MedicalRecord recordFor(Patient patient) {
    return new MedicalRecord(patient, List.of());
  }
}
