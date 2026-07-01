package io.nxmatic.rke2lab.osgi.runtime;

import java.util.List;
import java.util.Optional;

/**
 * The launch KNOBS that genuinely differ between executors — everything else the {@link
 * FrameworkLauncher} derives from the {@link io.nxmatic.rke2lab.osgi.boot.discovery.BootPlan} (e.g.
 * pax-presence from whether the plan carries pax bundles). Two honest knobs:
 *
 * <ul>
 *   <li>{@link #bootDelegation} — packages every bundle loads from the parent (app) classloader,
 *       bypassing import/export wiring. For JDK-internal packages a library reaches reflectively
 *       without importing them — notably {@code sun.misc} for byte-buddy's {@code
 *       ClassInjector.UsingReflection}. A system-bundle EXPORT cannot serve these (the consumer
 *       never declares the import to wire to). Empty in prod; set by the test executor.
 *   <li>{@link #felixLogLevel} — Felix's own {@code felix.log.level} (the resolver's internal
 *       trace, the only place a failed resolve explains WHICH requirement could not be wired). Null
 *       leaves the Felix default; the test executor raises it via {@code @FrameworkLog}.
 * </ul>
 */
public record LaunchConfig(List<String> bootDelegation, Optional<Integer> felixLogLevel) {

  public LaunchConfig {
    bootDelegation = List.copyOf(bootDelegation);
  }

  /** The prod default: no boot-delegation, Felix's default log level. */
  public static LaunchConfig defaults() {
    return new LaunchConfig(List.of(), Optional.empty());
  }

  public LaunchConfig withBootDelegation(String... packages) {
    return new LaunchConfig(List.of(packages), felixLogLevel);
  }

  public LaunchConfig withFelixLogLevel(int level) {
    return new LaunchConfig(bootDelegation, Optional.of(level));
  }
}
