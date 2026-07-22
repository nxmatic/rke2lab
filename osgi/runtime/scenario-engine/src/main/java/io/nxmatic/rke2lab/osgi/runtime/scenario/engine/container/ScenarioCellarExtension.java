package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.impl.ScenarioHolder;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.BaseWorldExtension;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The transactional-cellar bracket: injects the run's {@link ScenarioCellar} before the body, and
 * at the boundary DRAINS the accumulated tags to the durable backend — but only for a {@link
 * RunRole#ROOT} run that succeeded. A {@link RunRole#FRAGMENT} is inert (its tags ride the graft up
 * to the root); a failure drains nothing (the model dies with the run — atomicity is free, the
 * disposable model IS the buffer). See docs/architecture/osgi/seed-broker-spec.adoc (§
 * cellar-transactional).
 *
 * <p>Dual-loaded (host-flat + one copy per bundle), and the OWNER of the cellar's lifecycle: it
 * builds the {@link ScenarioCellar}, hands it the durable-read {@code Supplier}, and drains at the
 * boundary. Nothing is inferred: the {@link RunRole} is read from the launcher session store
 * (seeded by whoever launched — {@code Main} seeds ROOT, a sown scion's own session defaults to
 * FRAGMENT). The durable resolution is DUAL-REALM by the instance's realm: a host-flat root through
 * the world connection ({@link BaseWorldExtension}'s class store), an in-container scion through
 * its own bundle registry ({@code ScenarioRegistry}) — the route it used before the cellar was
 * injected.
 */
public final class ScenarioCellarExtension
    implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

  /** How long the drain waits for the durable cellar to be resolvable in the registry. */
  private static final long RESOLVE_TIMEOUT_MILLIS = 5_000;

  @Override
  @SuppressWarnings("unchecked")
  public void beforeTestExecution(ExtensionContext context) {
    if (!(context.getRequiredTestInstance() instanceof CellarReceiver<?>)) {
      return;
    }
    // The extension always injects a ScenarioCellar; a scenario binds CellarReceiver<Cellar> (store
    // only) or CellarReceiver<ScenarioCellar> (also transactionId()) — both satisfied by the same
    // instance, so the cast is safe (the SessionSeed pattern). It IS the transaction's
    // lifecycle-mate, so IT posts the tx-id tag on the model (the sole writer of a tag) for audit
    // correlation; the graft folds it into the root tree, a scion sowing a sub-scion reads it back
    // via transactionId(). The model is read LAZILY (jGiven binds it before the body); the durable
    // read side is resolved on first fetch.
    final CellarReceiver<ScenarioCellar> receiver =
        (CellarReceiver<ScenarioCellar>) context.getRequiredTestInstance();
    final String txId = TxIdSeed.read(context).orElse("");
    final ScenarioCellar cellar =
        new ScenarioCellar(
            ScenarioCellarExtension::currentModel, () -> resolve(context, Cellar.class), txId);
    // The ALLER sense of the transaction: re-post the parent's in-flight entries onto THIS model
    // (marked inherited), BEFORE the body, so the scion reads its parent's stores as its own
    // overlay
    // (read-your-parent's-writes) — posted first, so a shared coordinate resolves own > inherited >
    // durable. The cellar is the sole tag writer, so the re-posting is its own method. Empty for a
    // run with no parent transaction (a root, or an isolated test).
    cellar.inheritEntries(CellarEntriesSeed.read(context));
    receiver.receiveCellar(cellar);
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    if (role(context) != RunRole.ROOT) {
      // A fragment never drains — its tags ride the graft up to the root. But the inherited entries
      // it read as its overlay (the parent's write-set) must NOT ride up: the trunk already holds
      // them (the parent crossing posted them), so strip them before the front-door serialises this
      // model, or they would fold up a second time and drain twice.
      ScenarioCellar.stripInherited(currentModel());
      return;
    }
    if (context.getExecutionException().isPresent()) {
      return; // failure → nothing persisted (the disposable model IS the transaction)
    }
    drain(currentModel(), resolve(context, OpaqueCellar.class));
  }

  /**
   * Replay the run's read-write set onto the durable backend, IN ORDER and opaquely (no decode): a
   * store re-stores the envelope, a tombstone withdraws the case. Order matters — a
   * store→withdraw→store sequence must land the case present, so the drain applies each entry as it
   * rode the model, not a collapsed final state.
   */
  private void drain(ReportModel model, OpaqueCellar durable) {
    for (ScenarioCellar.Entry entry : ScenarioCellar.entriesOf(model)) {
      if (entry.tombstone()) {
        durable.withdraw(entry.parcel(), coordinateOf(entry.envelope()));
      } else {
        durable.store(entry.parcel(), entry.envelope());
      }
    }
  }

  /**
   * The neutral {@link SeedCoordinate} an envelope names (domain + slug) — for the opaque replay.
   */
  private static SeedCoordinate coordinateOf(SeedEnvelope envelope) {
    return new SeedCoordinate() {
      @Override
      public String slug() {
        return envelope.coordinate();
      }

      @Override
      public String domain() {
        return envelope.domain();
      }
    };
  }

  private static ReportModel currentModel() {
    return ScenarioHolder.get().getScenarioOfCurrentThread().getModel();
  }

  private static RunRole role(ExtensionContext context) {
    return RunRoleSeed.read(context);
  }

  /**
   * Resolve a durable collaborator — DUAL-REALM, by the instance's own realm (the discriminant the
   * scion already carries): a host-flat root resolves through the world's connection (the class
   * store {@code BaseWorldExtension} owns); an in-container scion resolves through its OWN bundle
   * registry ({@code ScenarioRegistry.of(instance)} — the very route {@code resolveCellar()} used
   * before the cellar was injected). One resolution concept, the realm picks the door.
   */
  private static <T> T resolve(ExtensionContext context, Class<T> type) {
    final OsgiConnection connection =
        context
            .getStore(BaseWorldExtension.NAMESPACE)
            .get(BaseWorldExtension.CONNECTION, OsgiConnection.class);
    if (connection != null) {
      final T service = connection.awaitService(type, RESOLVE_TIMEOUT_MILLIS);
      if (service == null) {
        throw new IllegalStateException(
            "no "
                + type.getSimpleName()
                + " in the registry within "
                + RESOLVE_TIMEOUT_MILLIS
                + "ms");
      }
      return service;
    }
    // No world connection ⇒ an in-container scion: resolve through its own bundle registry. The
    // durable cellar is host-registered (not a delayed SCR component), so releasing the registry at
    // once — try-with-resources — hands back a still-valid service without holding the get open;
    // the
    // scenario-lifetime hold belongs to the @OsgiService injection path (OsgiServiceExtension), not
    // this one-shot durable lookup.
    try (ScenarioRegistry registry = ScenarioRegistry.of(context.getRequiredTestInstance())) {
      return registry.require(type, "no " + type.getSimpleName() + " in the scion's registry");
    }
  }
}
