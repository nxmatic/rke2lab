package io.seedmatic.rke2lab.netplan.cli.bdd;

import java.util.Optional;

/**
 * The one driver-captured fact the netplan CLI seeds into {@link NetplanCliScenario}: the plot the
 * scion writes {@code blueprint.json} into (the {@code SOIL} amendment the sow carries). The CLI
 * always supplies it — a temp dir it creates as the export staging area, then reads the JSON back
 * from and converts to YAML on stdout. {@link Optional#empty()} lets the scion fall to its own temp
 * dir (a bare survey), never a blank string.
 */
public record NetplanCliRun(Optional<String> materializationRoot) {

  public static NetplanCliRun of(Optional<String> materializationRoot) {
    return new NetplanCliRun(materializationRoot);
  }
}
