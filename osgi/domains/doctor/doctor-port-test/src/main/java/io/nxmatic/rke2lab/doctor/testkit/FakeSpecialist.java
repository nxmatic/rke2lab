package io.nxmatic.rke2lab.doctor.testkit;

import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.Map;
import java.util.Optional;

/**
 * A schema-self-describing, prescribing {@link Specialist} double for the model tests. It stands in
 * for a host specialist (e.g. the dbus one) wherever a test only needs "a specialist on this domain
 * that prescribes" to exercise the Generalist's fan-out / collection — never the host specialist's
 * own behaviour, which a dedicated host test covers.
 *
 * <p>A {@code Specialist} answers through a self-describing schema, so a test asserts against THIS
 * fake's own {@link #SCHEMA_REF} / {@link #PROGRAM_REF} (stable, host-independent), not a real
 * specialist's. Routes on {@link Specialty#SYSTEMD} by default — the domain {@code
 * CONNECTION_REFUSED} routes to — so it slots into the existing consult tests unchanged.
 */
public final class FakeSpecialist implements Specialist {

  /** The fake's stable assessment schema — what tests assert on instead of a host schema. */
  public static final SchemaRef SCHEMA_REF = SchemaRef.of("test/fake-specialist/v1");

  /** The fake's stable prescription program. */
  public static final RemediationProgramRef PROGRAM_REF = RemediationProgramRef.RESTART_UNIT;

  /** The fake's stable target unit — what {@code payload.get("unit")} carries. */
  public static final String UNIT = "fake-specialist.service";

  private final Specialty domain;

  public FakeSpecialist() {
    this(Specialty.SYSTEMD);
  }

  public FakeSpecialist(Specialty domain) {
    this.domain = domain;
  }

  @Override
  public Specialty domain() {
    return domain;
  }

  @Override
  public Assessment assess(Referral referral) {
    return Assessment.of(
        SCHEMA_REF,
        Map.of("symptom", referral.symptom().id()),
        "fake specialist reasoning for " + referral.symptom().id());
  }

  @Override
  public Optional<Prescription> prescribe(Referral referral, Assessment assessment) {
    return Optional.of(
        Prescription.of(
            PROGRAM_REF,
            Map.of("symptom", referral.symptom().id(), "unit", UNIT),
            "systemctl restart " + UNIT));
  }
}
