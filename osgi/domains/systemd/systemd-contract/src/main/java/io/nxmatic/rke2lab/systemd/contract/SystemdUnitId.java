// @codebase
package io.nxmatic.rke2lab.systemd.contract;

/**
 * A typed systemd unit identifier — the single source of truth for the units the systemd domain
 * owns and that cross module boundaries. A unit name is a RELATIONSHIP in disguise: the same string
 * is a producer in {@code manifests} (the {@code SystemdService} construct that synthesises the
 * unit) and a consumer in {@code doctor-core} (the specialist that remediates it). Promoting the
 * literal to a typed id here, in the systemd domain's port, is what draws that producer→consumer
 * edge in the import graph — the real purpose of single-sourcing is traceability, not
 * typo-avoidance.
 *
 * <p>Two forms because the two sides ask for different shapes: the cdk8s {@code SystemdService}
 * construct is built from the BARE id (it appends {@code .service} itself), while a consumer that
 * names the running unit — a {@code systemctl} target, an {@code After=} line — needs the
 * fully-qualified {@code .service} file name.
 */
public enum SystemdUnitId {

  /** The unit exposing the DBus system bus over TCP — the dbus-systemd edge's endpoint. */
  DBUS_TCP_SYSTEM_BUS("rke2lab-dbus-tcp-system-bus");

  private static final String SERVICE_SUFFIX = ".service";

  private final String bareName;

  SystemdUnitId(String bareName) {
    this.bareName = bareName;
  }

  /**
   * The bare unit id, no suffix — what the cdk8s {@code SystemdService} construct is built from.
   */
  public String bareName() {
    return bareName;
  }

  /** The fully-qualified {@code .service} file name — what a consumer naming the unit needs. */
  public String serviceUnitName() {
    return bareName + SERVICE_SUFFIX;
  }
}
