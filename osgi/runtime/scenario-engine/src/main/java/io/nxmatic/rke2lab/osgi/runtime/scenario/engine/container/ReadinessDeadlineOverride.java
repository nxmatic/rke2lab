package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.time.Duration;
import java.util.Optional;

/**
 * The stack config's say over the readiness deadlines — each half optional, absent meaning "keep
 * the {@code @ReadinessDeadlines} default the code declares". The host builds one from Pulumi
 * ({@code rke2lab:readiness:connectTimeout} / {@code :timeout}) and seeds it into the launcher
 * session store ({@link ReadinessBudgetExtension#into}); the extension reads it back and folds it
 * over the annotation defaults. A plain value crossing the launcher boundary IN-REALM (two JDK
 * {@code Duration}s, no codec) — the same hop the scenario input rides.
 *
 * <p>{@link #NONE} is the no-override the extension falls to when the host seeded nothing — every
 * deadline then comes from the annotation.
 */
public record ReadinessDeadlineOverride(Optional<Duration> connect, Optional<Duration> ready) {

  /** No override — both deadlines come from the {@code @ReadinessDeadlines} annotation. */
  public static final ReadinessDeadlineOverride NONE =
      new ReadinessDeadlineOverride(Optional.empty(), Optional.empty());
}
