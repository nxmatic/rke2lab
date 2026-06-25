package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.*;

/**
 * Renders the doctor's one-line consultation narration ("consulted with N prior visit(s); SYMPTOM
 * seen K× before") from a {@link MedicalRecord}. Extracted so the two checkpoint stages that
 * consult render the line identically rather than each duplicating the string assembly.
 */
public final class ConsultationNarration {

  private ConsultationNarration() {}

  public static String consultedLine(MedicalRecord record, Symptom symptom) {
    return "consulted with "
        + record.visits().size()
        + " prior visit(s); "
        + symptom.id()
        + " seen "
        + record.historyOf(symptom).count()
        + "× before";
  }
}
