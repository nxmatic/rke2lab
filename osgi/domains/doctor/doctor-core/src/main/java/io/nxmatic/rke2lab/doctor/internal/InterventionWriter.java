package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionWire;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;

/**
 * Wraps a doctor-side {@link Intervention} into the canonical {@code intervention} {@link
 * SeedEnvelope} the write seam carries — the one place that projects an {@link Intervention} onto
 * its {@link InterventionWire} and encodes it with the realm's {@link SeedCodec}, shared by both
 * producers (the intervention ingress SeedHandler, {@code DriftSpecialist} inference) so the
 * canonical shape is written once. The {@link Intervention} (and the doctor vocabulary its refs
 * carry) stays OSGi-internal; only the flat {@link SeedEnvelope} crosses the seam.
 */
final class InterventionWriter {

  private static final SeedCodec CODEC = new SeedCodec();

  private InterventionWriter() {}

  /**
   * The canonical {@code intervention} SeedEnvelope carrying this intervention's {@link
   * InterventionWire}.
   */
  static SeedEnvelope of(Intervention intervention) {
    final InterventionWire wire =
        new InterventionWire(
            intervention.provenance().id(),
            intervention.when(),
            intervention.what(),
            intervention.problem().toRef(),
            intervention.prescriptionRef().map(ref -> ref.id()),
            intervention.details());
    return SeedEnvelope.of(DoctorCoordinate.INTERVENTION, CODEC.encode(wire));
  }
}
