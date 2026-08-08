package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.junit5.JGivenExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The socle every seed scenario wears — the console-report bracket ({@link
 * ConsoleReportExtension}), jGiven ({@link JGivenExtension}), the transactional cellar ({@link
 * ScenarioCellarExtension}), the OSGi-service injection bridge ({@link OsgiServiceExtension}), and
 * the outbound outcome channel ({@link ScenarioOutcomeExtension}), the extensions that ALWAYS go
 * together on a scenario (whether a host root or an in-container scion). One meta-annotation
 * instead of repeating them, the way {@code @SeedRuntime} composes its own extension.
 *
 * <p>{@link ConsoleReportExtension} is declared FIRST on purpose: it BRACKETS the jGiven lifecycle
 * to keep jGiven's plain-text report off the console (the engine harvests the model instead).
 * Registered first, its {@code beforeAll} disables the report before jGiven starts and its {@code
 * afterAll} (reverse order → runs LAST) restores the prior value AFTER {@code
 * JGivenExtension.afterAll} has already emitted the — now suppressed — report.
 *
 * <p>It does NOT include {@code @SeedRuntime} (the world lifecycle): a scion plays INSIDE the world
 * the host booted and resolves through its own bundle registry, so only the host ROOT adds
 * {@code @SeedRuntime} alongside this — it owns the connection. Named for the runtime family
 * ({@code SeedRuntime}, {@code SeedScenario}), NOT {@code @Scenario} which would clash with
 * jGiven's {@code com.tngtech.jgiven.impl.Scenario} imported by every scenario.
 *
 * <p>Order matters for {@link ScenarioOutcomeExtension}: it is declared LAST, and Jupiter runs
 * after-callbacks in REVERSE registration order, so it fires BEFORE {@code
 * JGivenExtension.afterTestExecution} removes the scenario from the {@code ScenarioHolder} — i.e.
 * the {@code ReportModel} is still bound when the outcome channel reads it (the same read {@link
 * ScenarioCellarExtension} does at its own boundary).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(ConsoleReportExtension.class)
@ExtendWith(JGivenExtension.class)
@ExtendWith(SurveyRenderExtension.class)
@ExtendWith(ScenarioCellarExtension.class)
@ExtendWith(OsgiServiceExtension.class)
@ExtendWith(ReadinessBudgetExtension.class)
@ExtendWith(ScenarioOutcomeExtension.class)
public @interface SeedScenario {}
