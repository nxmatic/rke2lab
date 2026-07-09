package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;

/**
 * The Layer-1 host READ port for a patient's record timeline: the host walks the Pulumi stack
 * history and yields one opaque {@code visit} {@link SeedEnvelope} per readable entry, WITHOUT
 * interpreting its medical content. Each SeedEnvelope's payload carries the entry's {@code version}
 * + {@code when} and the RAW consultation-report and expectation graft blobs collected from that
 * snapshot — the host is the producer of the wire shape, not a reader of doctor form.
 *
 * <p>OSGi rebuilds the {@code MedicalRecord} from these blobs INSIDE the bundle realm (the moved
 * readers, behind {@link MedicalRecordRegistry}); no {@code doctor.records} type ever crosses this
 * seam — only {@link Patient} (the read key) and {@link SeedEnvelope} (the opaque blob).
 */
public interface MedicalRecordJournal {

  /**
   * The patient's own stack timeline, oldest first, one {@code visit} {@link SeedEnvelope} per
   * readable history entry. An absent or empty timeline yields an empty list (a legitimate
   * nothing-here); a present-but-unreadable history is the implementation's concern and degrades to
   * the readable prefix rather than throwing into the diagnosis path.
   */
  List<SeedEnvelope> historyOf(Patient patient);

  /**
   * The current patient's cohort — the sibling patients sharing the backend (the patient's own
   * first). Sibling enumeration is Layer-1 host knowledge (which stacks exist under the backend);
   * the grant filter is applied by the model, not here. With no backend, the cohort is just the
   * current patient.
   */
  List<Patient> cohort(Patient current);
}
