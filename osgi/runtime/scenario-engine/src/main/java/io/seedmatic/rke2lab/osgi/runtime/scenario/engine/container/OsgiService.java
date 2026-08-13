package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a scenario field the {@link OsgiServiceExtension} INJECTS from the scenario bundle's OSGi
 * registry before the body runs — the push bridge between jGiven and DS. It is the field-injection
 * counterpart of SCR's {@code @Reference}: a scenario is instantiated by the JUnit engine, not by
 * SCR, so SCR cannot inject it; this annotation + its extension close that gap, so a scenario
 * DECLARES its collaborators instead of pulling each one by hand ({@code
 * ScenarioRegistry.of(this).require(...)}).
 *
 * <p>The field is always an {@code Optional<T>} (uniform, and never null — so the null-hygiene
 * discipline holds without a {@code @MonotonicNonNull} guard or a hand-written getter). What {@link
 * #await} controls is the RESOLUTION, not the field shape:
 *
 * <ul>
 *   <li>{@code await = true} (the default — a required collaborator): the extension AWAITS SCR up
 *       to the registry timeout, because a delayed DS component publishes its service async to
 *       {@code bundle.start()} (the SCR race). The body unwraps it with {@code orElseThrow(...)} —
 *       an absent required service is a wiring bug, surfaced loud.
 *   <li>{@code await = false} (an optional collaborator, e.g. the doctor): the extension takes an
 *       immediate SNAPSHOT — {@link java.util.Optional#empty()} when none is published (a world
 *       booted without it), never awaited. The body reads it with {@code map}/{@code ifPresent}.
 * </ul>
 *
 * <p>Opt-in per field, and per scenario: a scenario with no {@code @OsgiService} field is untouched
 * (the host-flat root, which receives its world through {@code ConnectionReceiver}, declares none).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OsgiService {

  /**
   * Whether to AWAIT SCR to publish a delayed component (a required collaborator) or take an
   * immediate snapshot (an optional one). Defaults to {@code true} — required, the DS
   * {@code @Reference} norm; an optional collaborator declares {@code await = false}.
   */
  boolean await() default true;
}
