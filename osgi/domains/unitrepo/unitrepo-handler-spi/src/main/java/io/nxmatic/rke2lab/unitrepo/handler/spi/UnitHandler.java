package io.nxmatic.rke2lab.unitrepo.handler.spi;

import org.osgi.annotation.bundle.Capability;

/**
 * The handler SPI a unit binds to via its constitutive {@code osgi.extender} requirement. One
 * handler serves all units of a given type; behaviour is resolved, not embedded. This interface
 * lives on the shared (parent) class loader so a handler loaded from the store casts safely.
 *
 * <p>The bundle carrying this SPI PROVIDES the {@code osgi.extender=unitrepo.handler} contract —
 * the {@link Capability} below makes bnd generate the {@code Provide-Capability} header from this
 * Java source. A unit that needs handling declares the matching {@code Require-Capability}; the
 * resolver wires it here. There is no concrete handler implementor yet (the contract is anticipated
 * by the unitrepo V1 design), so this is the providing half declared on its own — the requiring
 * half lands with the first specialist unit.
 */
@Capability(namespace = "osgi.extender", name = "unitrepo.handler", version = "1.0")
public interface UnitHandler {

  /** The unit type this handler manages, e.g. {@code unitrepo.type.visit}. */
  String handledType();

  /** Handle a unit's content. The living-entity operation; trivial in V1 (e.g. a summary). */
  String handle(String unitContent);
}
