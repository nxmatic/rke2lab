package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.util.List;
import java.util.Optional;
import org.junit.platform.engine.DiscoverySelector;
import org.osgi.framework.wiring.BundleWiring;

/**
 * How a {@link JUnitLauncherCore} run finds what to play — one of the two halves the core injects
 * (the other is {@link HarvestStrategy}). Given the host bundle's wiring when the core runs
 * in-container ({@link Optional#empty()} on the flat host classpath), it yields the discovery
 * selectors.
 *
 * <p>The two implementations that motivate the seam: the in-container envelope enumerates {@code
 * *Test} classes from the wiring ({@link BundleWiring#listResources}); a runtime pipeline (or a
 * flat test) selects a NAMED scenario class and ignores the absent wiring.
 */
@FunctionalInterface
public interface DiscoveryStrategy {

  /**
   * @param wiring the host bundle's wiring when running in-container, {@link Optional#empty()} when
   *     the core runs on the flat host classpath (discover by named class instead)
   */
  List<DiscoverySelector> selectors(Optional<BundleWiring> wiring);
}
