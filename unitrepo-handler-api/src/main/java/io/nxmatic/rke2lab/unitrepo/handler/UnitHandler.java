package io.nxmatic.rke2lab.unitrepo.handler;

/**
 * The handler SPI a unit binds to via its constitutive {@code osgi.extender} requirement. One
 * handler serves all units of a given type; behaviour is resolved, not embedded. This interface
 * lives on the shared (parent) class loader so a handler loaded from the store casts safely.
 */
public interface UnitHandler {

  /** The unit type this handler manages, e.g. {@code unitrepo.type.visit}. */
  String handledType();

  /** Handle a unit's content. The living-entity operation; trivial in V1 (e.g. a summary). */
  String handle(String unitContent);
}
