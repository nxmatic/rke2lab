package io.nxmatic.rke2lab.osgibench.host;

import org.osgi.annotation.bundle.Capability;

/**
 * Disposable bench stand-in for the host bundle that runs the config-delivery extenders. It
 * declares — via {@link Capability} on the {@code osgi.extender} namespace — that it PROVIDES the
 * metatype and component extenders, so bnd generates the {@code Provide-Capability} header from
 * this Java source rather than from a hand-typed bnd line. {@code @Capability} (not
 * {@code @Component}) is the honest form: the host advertises an arbitrary capability it owns; it
 * is not itself a DS component.
 */
@Capability(namespace = "osgi.extender", name = "osgi.metatype", version = "1.4")
@Capability(namespace = "osgi.extender", name = "osgi.component", version = "1.5")
public final class HostComponent {
  private HostComponent() {}
}
