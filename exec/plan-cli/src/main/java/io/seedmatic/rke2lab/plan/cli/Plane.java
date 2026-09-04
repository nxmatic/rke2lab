package io.seedmatic.rke2lab.plan.cli;

/**
 * The planes the {@code plan} CLI exports — the genus/species split that motivates the unified CLI:
 * {@code plan} is the genus, {@link #NETWORK} (netplan) and {@link #DATASET} (dataplan) the
 * species. Each plane names the broker coordinate its {@code export} sows, the export file its
 * in-container scion writes into the SOIL, and how the reaped host-neutral JSON is rendered to
 * stdout.
 *
 * <p>The coordinate slug is a plain string, NOT the domain's {@code *Coordinate.DOMAIN} constant:
 * those live in {@code type=contract} bundles the flat host cannot reference (the realm-boundary
 * law), so the CLI sows the literal slug — the same host-flat discipline the former netplan CLI
 * held.
 */
public enum Plane {
  NETWORK("netplan", "blueprint.json", Format.YAML),
  DATASET("dataplan", "dataplan.json", Format.JSON);

  /** How the reaped host-neutral JSON is rendered to stdout. */
  public enum Format {
    YAML,
    JSON
  }

  private final String coordinate;
  private final String exportFile;
  private final Format format;

  Plane(String coordinate, String exportFile, Format format) {
    this.coordinate = coordinate;
    this.exportFile = exportFile;
    this.format = format;
  }

  /** The broker coordinate slug this plane's {@code export} sows through the gardening. */
  public String coordinate() {
    return coordinate;
  }

  /** The file the in-container scion writes into the SOIL, read back by the CLI. */
  public String exportFile() {
    return exportFile;
  }

  /** The stdout rendering of the reaped export. */
  public Format format() {
    return format;
  }

  /** Resolve a plane from its CLI token; an unknown token is a usage error. */
  public static Plane parse(String token) {
    return switch (token) {
      case "network" -> NETWORK;
      case "dataset" -> DATASET;
      default ->
          throw new IllegalArgumentException(
              "unknown plane '" + token + "' — supported: network, dataset");
    };
  }
}
