package io.seedmatic.rke2lab.osgi.boot.discovery;

import java.util.List;
import java.util.Set;

/**
 * The decision a boot makes BEFORE any framework exists: which bundles to install, each pinned to a
 * start level, and the {@code system.packages.extra} the model bundles need mirrored from their
 * {@code Import-Package}. A pure value computed by {@link BootPlanner} — inspectable, assertable
 * and loggable without booting Felix, which is the whole point of telling the boot DECISION apart
 * from the boot ACT (the launcher consumes this plan, see {@code osgi/runtime}).
 *
 * <p>The start levels are the framework's native activation order: lower activates first. The plan
 * carries them on each {@link Installable} so the launcher pins and raises, never hand-orders. The
 * beginning start level (the level the framework climbs to on start) is the highest a bundle sits
 * at — exposed as {@link #beginningStartLevel()} so the launcher need not know the level
 * vocabulary.
 */
public record BootPlan(
    List<Installable> installables, Set<String> systemPackagesExtra, boolean paxPresent) {

  /**
   * A passive spec/library jar (e.g. the DS-API trio felix.scr imports): no activator, it only
   * needs to be RESOLVABLE before anything that imports it, so it sits at the lowest level — before
   * the boot stack that wires to it.
   */
  public static final int START_LEVEL_PASSIVE = 1;

  /** Pax Logging: the LogService must be live before anything else activates. */
  public static final int START_LEVEL_LOGGING = 2;

  /** The felix runtime (felix.scr / resolver): after logging, before the model bundles. */
  public static final int START_LEVEL_FRAMEWORK_RUNTIME = 3;

  /** Model/edge bundles: they activate last, once everything they wire to is resolved. */
  public static final int START_LEVEL_BUNDLES = 4;

  public BootPlan {
    installables = List.copyOf(installables);
    systemPackagesExtra = Set.copyOf(systemPackagesExtra);
  }

  /** An installable bundle: where its bytes live, and the start level its role maps to. */
  public record Installable(BundleLocation location, int startLevel) {}

  /**
   * The level the framework climbs to when started — the highest level any bundle sits at, so every
   * bundle is activated when the framework reaches it. {@link #START_LEVEL_BUNDLES} on an empty
   * plan.
   */
  public int beginningStartLevel() {
    return installables.stream()
        .mapToInt(Installable::startLevel)
        .max()
        .orElse(START_LEVEL_BUNDLES);
  }
}
