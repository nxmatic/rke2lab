package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

/**
 * A scenario that RECEIVES the world's {@link OsgiConnection} from {@link BaseWorldExtension} — the
 * bridge from the class-scope world (the extension owns it) to the scenario body (a jGiven {@code
 * Stage} has no {@code ExtensionContext}). The extension's {@code TestInstancePostProcessor} hands
 * the connection here before the body runs, so the host GIVEN does {@code
 * Gardening.over(connection)} instead of {@code Gardening.open()} — one Felix, owned by the
 * extension, not a second one booted per run.
 *
 * <p>Opt-in by implementing this: a scenario that does not receive a connection is left untouched
 * (the in-container scions resolve their world through {@code ScenarioRegistry}, not this).
 */
@FunctionalInterface
public interface ConnectionReceiver {

  /** Receive the class-scope world connection, before the test body runs. */
  void receiveConnection(OsgiConnection connection);
}
