package io.seedmatic.rke2lab.dataplan.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The dataplan domain's seed coordinates, declared and owned in ONE place — the single-source
 * discipline its sibling {@code NetplanCoordinate} holds, and, like it, an EMPTY {@code enum}:
 * dataplan SYNTHESISES-and-MATERIALISES (the layout export writes {@code dataplan.json} into the
 * soil and stores no harvest behind the cellar), so it owns no value-coordinate. It enumerates
 * nothing, holding instead, as static constants, the shared META coordinates dataplan is addressed
 * through — {@link #AMEND} (the reflector serves it, the assembler gathers on it), {@link #SHAPE}
 * (the schema projection), {@link #RUNBOOK} (the export trigger) — all keyed by one {@link
 * #DOMAIN}, so the growers never diverge as raw literals.
 */
public enum DataplanCoordinate implements SeedCoordinate {
  ;

  /** The domain slug every dataplan coordinate is keyed by — the single source. */
  public static final String DOMAIN = "dataplan";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the layout export through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  @Override
  public String slug() {
    throw new UnsupportedOperationException(
        "DataplanCoordinate is an empty enum with no slug of its own — use its AMEND / SHAPE /"
            + " RUNBOOK meta-coordinate constants");
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
