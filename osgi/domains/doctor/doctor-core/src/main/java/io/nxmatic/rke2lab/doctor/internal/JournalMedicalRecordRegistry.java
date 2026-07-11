package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The OSGi-side {@link MedicalRecordRegistry}: it folds the host {@link Cellar}'s opaque {@code
 * visit} {@link SeedEnvelope}s into a {@link MedicalRecord} INSIDE the bundle realm, via the moved
 * {@link MedicalRecordReader} (and the readers behind it). The host produces the blobs by reading
 * the Pulumi stack (its {@code Cellar} impl); this component is the SOLE place that interprets
 * their medical content — the leak (a {@code doctor.records} value crossing host→OSGi) is gone.
 *
 * <p>It consumes the neutral {@link Cellar} by a {@link Parcel} and projects {@code Patient ↔
 * Parcel} at that seam ({@link ParcelProjection}) — the doctor reasons over a {@link Patient}, the
 * host stores/fetches by a {@link Parcel}, neither word crossing. Published as the {@code
 * MedicalRecordRegistry} service the institution ({@code DefaultHealthSystem}) references. Per the
 * registry contract {@link #recordFor} never throws: a {@link MedicalRecordReconstructionException}
 * degrades to the partial record the reader built. The per-patient memoization matches the old live
 * registry (one fold per patient per run).
 */
@Component(service = MedicalRecordRegistry.class)
public final class JournalMedicalRecordRegistry implements MedicalRecordRegistry {

  private final Cellar cellar;
  private final MedicalRecordReader reader = new MedicalRecordReader();
  private final Map<Patient, MedicalRecord> cache = new ConcurrentHashMap<>();

  @Activate
  public JournalMedicalRecordRegistry(@Reference Cellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public MedicalRecord recordFor(Patient patient) {
    return cache.computeIfAbsent(patient, this::fold);
  }

  /**
   * The current patient's cohort — every sibling stack the journal enumerates, each folded via
   * {@link #recordFor}, the current patient first. The grant filter is applied by {@code
   * ClinicalAccess}, not here.
   */
  @Override
  public List<MedicalRecord> cohortFor(Patient current) {
    final List<MedicalRecord> cohort = new ArrayList<>();
    for (Parcel sibling : cellar.neighbours(ParcelProjection.parcelOf(current))) {
      cohort.add(recordFor(ParcelProjection.patientOf(sibling)));
    }
    return cohort;
  }

  private MedicalRecord fold(Patient patient) {
    try {
      return reader.read(patient, cellar.fetch(ParcelProjection.parcelOf(patient)));
    } catch (MedicalRecordReconstructionException ex) {
      // The reader already builds a partial on failure; surface it rather than discarding readable
      // visits — the registry's deliberate degrade, never a swallow.
      return ex.partialRecord();
    }
  }
}
