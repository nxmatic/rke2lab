package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

/**
 * Marks a scenario SURVEY-INERT: it does NOT run under a surveying gate. A PURE PROBE scenario
 * (systemd adapter, cluster readiness) has no honest plan-only shape — its output IS the live state
 * (a kubectl/dbus read), so a surveying run could only fabricate an observation or contact a system
 * that is not there. So instead of running, it renders every step PENDING and touches nothing: the
 * {@link SurveyRenderExtension} installs a {@link SurveyInertScenarioExecutor} that skips the
 * bodies.
 *
 * <p>Contrast a MATERIALISER, which has an honest survey plan and gets a {@code Cultivating}/{@code
 * Surveying} collaborator PAIR at the frontier — its bodies still run (against the surveying impl),
 * only the render is rewritten PENDING. A probe cannot be split (there is nothing to plan), so this
 * marker is how a scenario declares "I have no survey — skip me, render me pending". The criterion
 * is the no-fake law: a survey must never fabricate a live observation.
 *
 * <p>A pure marker (the {@link ScenarioPlayer.Playable} shape): no methods. A scenario that does
 * NOT implement it is surveyed the materialiser way (bodies run, render pending).
 */
public interface SurveyInert {}
