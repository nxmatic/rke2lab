package io.seedmatic.rke2lab.seed.broker.port;

import java.time.Duration;
import java.util.Optional;

/**
 * One readiness checkpoint's say over its two deadlines — each half optional, absent meaning "keep
 * the {@code @ReadinessDeadlines} default the code declares". Carried inside {@link
 * ReadinessOverrides} (the global default + the per-checkpoint map), the ambient fact the host
 * publishes for the scions — so it lives in the seam both realms share (a single system-exported
 * copy, like {@link RunGate}), never a dual-realm bundle type.
 *
 * <p>{@link #NONE} is the no-override the extension falls to when the host published nothing (or a
 * checkpoint the operator did not name) — every deadline then comes from the annotation.
 */
public record ReadinessDeadlineOverride(Optional<Duration> connect, Optional<Duration> ready) {

  /** No override — both deadlines come from the {@code @ReadinessDeadlines} annotation. */
  public static final ReadinessDeadlineOverride NONE =
      new ReadinessDeadlineOverride(Optional.empty(), Optional.empty());
}
