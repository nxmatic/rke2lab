package io.seedmatic.rke2lab.doctor.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The doctor domain's seed coordinates — the document types it grows behind the broker door. Doctor
 * OWNS this enum: it knows every coordinate it publishes, so contributing a handler and declaring
 * its coordinate are one act, in one place. There is no central coordinate registry — {@link
 * SeedCoordinate} is the foundation CONCEPT, this enum the doctor's CONTRIBUTION of it.
 *
 * <p>{@link #domain()} answers {@code "doctor"} for every constant: a coordinate belongs to its
 * domain by construction (it is declared here), so the owning domain is the coordinate's own
 * answer, not a shared {@code Domain} enum. Each wire-record binds to its coordinate by
 * {@code @SeedContract(slug)}; {@code SeedCodec.decode(SeedEnvelope, type)} verifies the envelope's
 * coordinate matches the record's declared slug at runtime.
 */
public enum DoctorCoordinate implements SeedCoordinate {
  READINESS_CHECKPOINT("readiness-checkpoint"),
  READINESS_VERDICT("readiness-verdict"),
  CONSULTATION("consultation"),
  INTERVENTION_REQUEST("intervention-request"),
  INTERVENTION("intervention"),
  VISIT("visit");

  private static final String DOMAIN = "doctor";

  private final String slug;

  DoctorCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
