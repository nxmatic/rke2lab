package io.seedmatic.rke2lab.plan.cli.bdd;

import io.seedmatic.rke2lab.plan.cli.Plane;
import java.util.Optional;

/**
 * The driver-captured facts the plan CLI seeds into {@link PlanCliScenario}: the {@link Plane} to
 * export (which names the coordinate the sow carries) and the plot the scion writes its export file
 * into (the {@code SOIL} amendment). The CLI always supplies the soil — a temp dir it creates as
 * the export staging area, then reads the export back from and renders on stdout. {@link
 * Optional#empty()} lets the scion fall to its own temp dir (a bare survey), never a blank string.
 */
public record PlanCliRun(Plane plane, Optional<String> materializationRoot) {

  public static PlanCliRun of(Plane plane, Optional<String> materializationRoot) {
    return new PlanCliRun(plane, materializationRoot);
  }
}
