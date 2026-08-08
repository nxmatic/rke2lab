// @codebase
package io.nxmatic.rke2lab.systemd.contract;

import io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;

/**
 * The readiness seam of the systemd domain — "await the live systemd converging to ready". The
 * consumer (the control-plane readiness path) calls it; an edge implements it by contacting systemd
 * over its dbus-on-TCP endpoint. The interface is the order, never the machine: it makes no
 * assumption about the transport, so the host obtains the implementation from the OSGi registry and
 * never holds a {@code DBusConnection} itself.
 *
 * <p>Ingress only for now. The same systemd door also affords egress (restart a unit via the {@code
 * systemd1.Manager}); when that face is needed it is a sibling seam, not a method here.
 */
public interface SystemdRuntimeProbe {

  /**
   * Reach the endpoint described by {@code request} and await systemd converging to ready, within
   * the two-tier {@code budget}: the reach is retried until the budget's connect deadline (a cold
   * boot or a fresh image re-seed lives here), then — once connected — dbus signals are awaited
   * until the ready deadline. Returns the systemd status at convergence, or the last not-ready
   * snapshot at the ready deadline; the endpoint staying unreachable through the connect deadline
   * is an unreachable snapshot (the node never booted far enough).
   */
  SystemdStatusSnapshot awaitReady(SystemdProbeRequest request, ReadinessBudget budget);
}
