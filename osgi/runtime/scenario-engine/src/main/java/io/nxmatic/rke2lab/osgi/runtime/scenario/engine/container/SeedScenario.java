package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.junit5.JGivenExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The socle every seed scenario wears — jGiven ({@link JGivenExtension}) + the transactional cellar
 * ({@link ScenarioCellarExtension}), the two extensions that ALWAYS go together on a scenario
 * (whether a host root or an in-container scion). One meta-annotation instead of repeating the
 * pair, the way {@code @SeedRuntime} composes its own extension.
 *
 * <p>It does NOT include {@code @SeedRuntime} (the world lifecycle): a scion plays INSIDE the world
 * the host booted and resolves through its own bundle registry, so only the host ROOT adds
 * {@code @SeedRuntime} alongside this — it owns the connection. Named for the runtime family
 * ({@code SeedRuntime}, {@code SeedScenario}), NOT {@code @Scenario} which would clash with
 * jGiven's {@code com.tngtech.jgiven.impl.Scenario} imported by every scenario.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(JGivenExtension.class)
@ExtendWith(ScenarioCellarExtension.class)
public @interface SeedScenario {}
