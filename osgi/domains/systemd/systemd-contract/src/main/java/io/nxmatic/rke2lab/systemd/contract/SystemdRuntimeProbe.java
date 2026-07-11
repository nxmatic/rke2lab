// @codebase
package io.nxmatic.rke2lab.systemd.contract;

/**
 * The readiness seam of the systemd domain — "produce the runtime status of the live systemd". The
 * consumer (the control-plane readiness path) calls it; an edge implements it by contacting systemd
 * over its dbus-on-TCP endpoint. The interface is the order, never the machine: it makes no
 * assumption about the transport, so the host obtains the implementation from the OSGi registry and
 * never holds a {@code DBusConnection} itself.
 *
 * <p>Ingress only for now. The same systemd door also affords egress (restart a unit via the {@code
 * systemd1.Manager}); when that face is needed it is a sibling seam, not a method here.
 */
public interface SystemdRuntimeProbe {

  /** Open the endpoint described by {@code request} and return the current systemd status. */
  SystemdStatusSnapshot probe(SystemdProbeRequest request);
}
