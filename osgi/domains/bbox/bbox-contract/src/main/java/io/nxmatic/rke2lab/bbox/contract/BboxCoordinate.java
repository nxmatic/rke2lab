package io.nxmatic.rke2lab.bbox.contract;

import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The bbox domain's seed coordinates — the document types it stores behind the cellar. Bbox OWNS
 * this enum: declaring a coordinate and using it are one act, so a store never spells a magic
 * string (the {@code "bbox"}/{@code "bbox-reservations"} literals the scion used to pass to {@code
 * new SeedEnvelope(...)} — the single-source-of-truth discipline). The slug matches the
 * {@code @SeedContract} of {@link BboxHarvest} ({@code bbox-reservations}), which {@code SeedCodec}
 * verifies at decode.
 *
 * <p>Beside its one VALUE coordinate ({@link #BBOX_RESERVATIONS}, what it stores) it holds — as
 * static constants, the way its sibling {@code ManifestsCoordinate} does — the META coordinates
 * bbox is addressed through: {@link #AMEND} (the reflector serves it, the assembler gathers on it)
 * and {@link #RUNBOOK} (the play-scenario trigger the handler serves), both keyed by the one {@link
 * #DOMAIN} slug so the OSGi-side growers never diverge as raw literals. The HOST keeps the {@code
 * "bbox"} literal instead — it runs in the FLAT realm the realm-boundary law forbids from
 * referencing a bundle-only contract type; that seam is guarded not by a shared constant but by
 * BETA (the assembler fails loud on a contributor whose coordinate no grower serves).
 */
public enum BboxCoordinate implements SeedCoordinate {
  BBOX_RESERVATIONS("bbox-reservations");

  private static final String DOMAIN = "bbox";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /**
   * The activation coordinate: the runbook handler plays the reconciliation scenario through it.
   */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

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
