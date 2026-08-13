package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

/**
 * The role a scenario is PLAYED in — whoever LAUNCHES it knows, and seeds it into the launcher
 * session store; the {@code ScenarioCellarExtension} reads it back to decide whether it drains.
 *
 * <p>The role is NOT a property of the scenario type: the same type is a {@link #FRAGMENT} when the
 * host sows it as a scion, a {@link #ROOT} when played alone in its own in-container test. So the
 * launcher declares it — {@code Main} and a domain's {@code *BddScenarios.run} (the isolated test)
 * seed {@link #ROOT}, the sow-graft seeds {@link #FRAGMENT}. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ cellar-transactional).
 */
public enum RunRole {

  /**
   * The transaction owner: at the run boundary, on success, it DRAINS the folded tags to durable.
   */
  ROOT,

  /**
   * A sown scion: it tags on its own model and never drains — the graft folds its tags to the root.
   */
  FRAGMENT;

  /** The session-store key both the launcher (seed) and the extension (read) address it by. */
  public static final String STORE_KEY = "run-role";
}
