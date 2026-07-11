package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The neutral gardening vocabulary a {@link Scion}'s role is drawn from — the single source of the
 * role names shared across the seam, so no call site spells a role as a magic string. A role is a
 * horticultural PART, never a domain's own field name: a domain maps its scions onto these (the
 * doctor's {@code consultationReport} is the {@link #FRUIT}, its {@code expectations} the {@link
 * #SOWING}), and the host selects a scion by its role without ever learning the domain's word.
 *
 * <p>String constants, not an enum, so a role is usable BOTH as an annotation element
 * ({@code @Scion(Role.FRUIT)} — an annotation value must be a constant expression) AND as the
 * Pulumi output KEY the split hands the write frontier and the read frontier harvests by (the same
 * discipline as {@code SystemdUnitCatalog}: define the identifier once, reference it everywhere).
 * The split verb returns {@code { rootstock -> { role -> value } }} keyed by these names, so the
 * write frontier nests each {@code role -> value} verbatim and the read frontier reads it back by
 * the same key — write and read cannot drift.
 */
public final class Role {

  /** The stored diagnostic report a consult produced — the plant's yield. */
  public static final String FRUIT = "fruit";

  /** The forward expectations a consult sowed for the next review — what is planted for later. */
  public static final String SOWING = "sowing";

  private Role() {}
}
