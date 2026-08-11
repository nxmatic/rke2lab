package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The incus domain's seed coordinates — the document types it stores behind the cellar. Incus OWNS
 * this enum: declaring a coordinate and using it are one act, in one place, so a store never spells
 * a magic string (the {@code "incus"}/{@code "incus-prep"} literals the scion used to pass to
 * {@code new SeedEnvelope(...)} — the single-source-of-truth discipline). Each slug matches the
 * {@code @SeedContract} of the wire-record it addresses ({@link IncusHarvest} → {@code
 * incus-prep}), which {@code SeedCodec} verifies at decode.
 */
public enum IncusCoordinate implements SeedCoordinate {
  INCUS_PREP("incus-prep");

  private static final String DOMAIN = "incus";

  private final String slug;

  IncusCoordinate(String slug) {
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
