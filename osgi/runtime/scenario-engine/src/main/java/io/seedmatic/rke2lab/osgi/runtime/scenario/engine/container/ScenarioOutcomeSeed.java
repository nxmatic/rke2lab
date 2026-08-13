package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.StoreScope;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The channel carrying a played scenario's {@link ScenarioOutcome} BACK to its driver across the
 * launcher boundary — the OUTBOUND twin of the inbound {@link ScenarioInputSeed}. It replaces the
 * static {@code LAST_RUNBOOK}/{@code LAST_CONSULTATIONS} holders: {@link ScenarioOutcomeExtension}
 * (the writer) PUTS the outcome at {@link StoreScope#LAUNCHER_SESSION} scope — the session-level
 * store, the launcher session's own {@link NamespacedHierarchicalStore} — and the front-door
 * harvest (the reader) READS it back from that same store once {@code launcher.execute} returns.
 *
 * <p>An INSTANCE (like {@link ScenarioInputSeed}), not a static helper: both ends construct one and
 * agree on the {@link Namespace} because it is derived from a constant key, so the writer's {@code
 * put} and the reader's {@code read} address the same slot. It carries no per-scenario state
 * (unlike {@link ScenarioInputSeed}, which is generic over the input type) — the outcome is always
 * the same {@link ScenarioOutcome} type under one key.
 *
 * <p>Why {@code LAUNCHER_SESSION}: an extension's per-test store is disposed when the test
 * finishes, before the harvest runs; only the session store outlives the execution and is reachable
 * by the harvest through {@code LauncherSession.getStore()} (the reader side {@link
 * JUnitLauncherCore#execute} holds). Both sides address it by the SAME {@link Namespace} parts, the
 * way the inbound seeds cross the two store views. The value is the live {@link ScenarioOutcome}
 * (in-realm hop, § {@link ScenarioOutcome}).
 */
public final class ScenarioOutcomeSeed {

  private static final String KEY = "scenario-outcome";

  private final String[] nsParts = {ScenarioOutcomeSeed.class.getName(), KEY};

  /**
   * PUT the outcome into the launcher SESSION store (via the {@link StoreScope#LAUNCHER_SESSION}
   * overload), so it outlives the test's own store and the harvest reads it after {@code execute}.
   */
  public void put(ExtensionContext context, ScenarioOutcome outcome) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) nsParts);
    context.getStore(StoreScope.LAUNCHER_SESSION, ns).put(KEY, outcome);
  }

  /**
   * READ the seeded outcome from the launcher session store the harvest holds. Required: on
   * {@code @SeedScenario} the {@link ScenarioOutcomeExtension} always writes it, so an absent
   * outcome is a wiring bug (a scenario that ran without the extension) — surfaced loud rather than
   * as a blind NPE on the caller's {@code .runbook()}.
   */
  public ScenarioOutcome read(NamespacedHierarchicalStore<Namespace> sessionStore) {
    return find(sessionStore)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no ScenarioOutcome in the session store — the scenario did not wear"
                        + " @SeedScenario (the ScenarioOutcomeExtension never seeded it)"));
  }

  /**
   * READ the seeded outcome, or {@code null} when none was seeded — the raw form {@link
   * ScenarioPlayer} uses to tell "a body ran and produced an outcome" apart from "the body never
   * ran" (a before-phase failure aborted the node), so it can surface the captured node failure
   * rather than a blind NPE. {@link #read} is the strict form for callers that know it is present.
   */
  public Optional<ScenarioOutcome> find(NamespacedHierarchicalStore<Namespace> sessionStore) {
    final Namespace ns = Namespace.create((Object[]) nsParts);
    return Optional.ofNullable(sessionStore.get(ns, KEY, ScenarioOutcome.class));
  }
}
