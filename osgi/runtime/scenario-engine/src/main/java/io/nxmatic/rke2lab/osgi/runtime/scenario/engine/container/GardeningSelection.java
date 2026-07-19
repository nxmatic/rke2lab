package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import java.util.Optional;

/**
 * The FRONTIER selector: turns the ambient {@link RunGate} into the LDAP filter that picks the
 * matching half of a {@code Cultivating}/{@code Surveying} collaborator pair from the registry. The
 * mode lives HERE, read once at the {@link OsgiServiceExtension} bridge, so every scenario stays
 * mode-blind — it declares {@code @OsgiService} collaborators and this decides which impl fills
 * them.
 *
 * <p>A mode-sensitive edge tags each impl with the {@link #PROPERTY} service property
 * ({@code @Component(property = "rke2lab.gardening=cultivating"|"surveying")}); an unpaired
 * collaborator (the cellar, the parcel, the doctor, the gate itself) carries none. The {@link
 * #filter()} matches the impl for the current mode OR any service that carries no gardening
 * property — one generic filter, no per-domain selector, no runtime ranking.
 */
final class GardeningSelection {

  /** The service property a mode-sensitive collaborator tags each of its two impls with. */
  static final String PROPERTY = "rke2lab.gardening";

  static final String CULTIVATING = "cultivating";
  static final String SURVEYING = "surveying";

  private final String mode;

  private GardeningSelection(String mode) {
    this.mode = mode;
  }

  /**
   * Read the ambient gate ONCE. An absent gate defaults to cultivating — a live run is the safe
   * default, and a survey is always a deliberate, host-published condition, never an accident.
   */
  static GardeningSelection from(Optional<RunGate> gate) {
    return new GardeningSelection(
        gate.map(RunGate::cultivating).orElse(true) ? CULTIVATING : SURVEYING);
  }

  /**
   * The LDAP filter matching the impl for this mode OR any service with no gardening property (the
   * unpaired collaborators). Passed to {@code getServiceReferences(type, filter)} — the type is the
   * {@code objectClass}, so this clause carries only the gardening discriminant.
   */
  String filter() {
    return "(|(" + PROPERTY + "=" + mode + ")(!(" + PROPERTY + "=*)))";
  }

  /** Whether the run is surveying — the RENDER axis reads this to install the pending marker. */
  boolean surveying() {
    return SURVEYING.equals(mode);
  }
}
