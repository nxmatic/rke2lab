package io.nxmatic.rke2lab.bbox.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The bbox domain's seed coordinates — the document types it stores behind the cellar. Bbox OWNS
 * this enum: declaring a coordinate and using it are one act, so a store never spells a magic
 * string (the {@code "bbox"}/{@code "bbox-reservations"} literals the scion used to pass to {@code
 * new SeedEnvelope(...)} — the single-source-of-truth discipline). The slug matches the
 * {@code @SeedContract} of {@link BboxHarvest} ({@code bbox-reservations}), which {@code SeedCodec}
 * verifies at decode.
 */
public enum BboxCoordinate implements SeedCoordinate {
  BBOX_RESERVATIONS("bbox-reservations");

  private static final String DOMAIN = "bbox";

  private final String slug;

  BboxCoordinate(String slug) {
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
