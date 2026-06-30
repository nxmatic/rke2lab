package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The OSGi-side {@link MedicalRecordRegistry}: it folds the host {@link MedicalRecordJournal}'s
 * opaque {@code visit} {@link Document}s into a {@link MedicalRecord} INSIDE the bundle realm, via
 * the moved {@link MedicalRecordReader} (and the readers behind it). The host produces the blobs by
 * reading the Pulumi stack; this component is the SOLE place that interprets their medical content
 * — the leak (a {@code doctor.records} value crossing host→OSGi) is gone.
 *
 * <p>Published as the {@code MedicalRecordRegistry} service the institution ({@code
 * DefaultHealthSystem}) references, so the host no longer publishes the registry — only the journal
 * the registry reads through. Per the registry contract {@link #recordFor} never throws: a {@link
 * MedicalRecordReconstructionException} degrades to the partial record the reader built. The
 * per-patient memoization matches the old live registry (one fold per patient per run).
 */
@Component(service = MedicalRecordRegistry.class)
public final class JournalMedicalRecordRegistry implements MedicalRecordRegistry {

  private final MedicalRecordJournal journal;
  private final MedicalRecordReader reader = new MedicalRecordReader();
  private final Map<Patient, MedicalRecord> cache = new ConcurrentHashMap<>();

  @Activate
  public JournalMedicalRecordRegistry(@Reference MedicalRecordJournal journal) {
    this.journal = journal;
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
    for (Patient sibling : journal.cohort(current)) {
      cohort.add(recordFor(sibling));
    }
    return cohort;
  }

  private MedicalRecord fold(Patient patient) {
    try {
      return reader.read(patient, journal.historyOf(patient));
    } catch (MedicalRecordReconstructionException ex) {
      // The reader already builds a partial on failure; surface it rather than discarding readable
      // visits — the registry's deliberate degrade, never a swallow.
      return ex.partialRecord();
    }
  }
}
