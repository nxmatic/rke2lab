package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import com.tngtech.jgiven.impl.ScenarioHolder;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.Tag;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
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
 * <p>Dual-loaded (host-flat + one copy per bundle). The role and the durable connection are read
 * from stores, never inferred: the {@link RunRole} from the launcher session store (seeded by
 * whoever launched — {@code Main}/{@code *BddScenarios.run} seed ROOT, the sow-graft seeds
 * FRAGMENT), the {@link OsgiConnection} from the class-{@code Store} the world extension ({@link
 * BaseWorldExtension}) owns — so the drain resolves the {@link OpaqueCellar} through the SAME
 * registry route the scions use, one resolution path.
 */
public final class ScenarioCellarExtension
    implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

  /** How long the drain waits for the durable cellar to be resolvable in the registry. */
  private static final long RESOLVE_TIMEOUT_MILLIS = 5_000;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    if (!(context.getRequiredTestInstance() instanceof CellarReceiver receiver)) {
      return;
    }
    // Build the run's cellar and hand it in. It is the transaction's lifecycle-mate, so IT posts
    // the
    // tx-id tag on the model (the sole writer of a tag — the extension does not) for audit
    // correlation; the graft folds it into the root tree, and a scion sowing a sub-scion reads it
    // back via cellar.transactionId(). The cellar reads the model LAZILY (jGiven binds it before
    // the
    // body, so getModel() is live at construction); the durable read side is resolved on first
    // fetch.
    final String txId = TxIdSeed.read(context).orElse("");
    receiver.receiveCellar(
        new ScenarioCellar(
            ScenarioCellarExtension::currentModel, () -> resolve(context, Cellar.class), txId));
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    if (role(context) != RunRole.ROOT) {
      return; // a fragment never drains — its tags ride the graft up to the root
    }
    if (context.getExecutionException().isPresent()) {
      return; // failure → nothing persisted (the disposable model IS the transaction)
    }
    drain(currentModel(), resolve(context, OpaqueCellar.class));
  }

  /** Re-store every accumulated cellar-entry tag to the durable backend, opaquely (no decode). */
  private void drain(ReportModel model, OpaqueCellar durable) {
    for (Tag tag : model.getTagMap().values()) {
      if (!ScenarioCellar.Tag.ENTRY.type().equals(tag.getType())) {
        continue;
      }
      for (String value : tag.getValues()) {
        final ScenarioCellar.Entry entry = codec.decode(value, ScenarioCellar.Entry.class);
        durable.store(entry.parcel(), entry.envelope());
      }
    }
  }

  private static ReportModel currentModel() {
    return ScenarioHolder.get().getScenarioOfCurrentThread().getModel();
  }

  private static RunRole role(ExtensionContext context) {
    return RunRoleSeed.read(context);
  }

  /** Resolve a durable collaborator through the world's connection — the scions' registry route. */
  private static <T> T resolve(ExtensionContext context, Class<T> type) {
    final OsgiConnection connection =
        context
            .getStore(BaseWorldExtension.NAMESPACE)
            .get(BaseWorldExtension.CONNECTION, OsgiConnection.class);
    if (connection == null) {
      throw new IllegalStateException(
          "no OsgiConnection in the class store — the scenario must @ExtendWith a world extension"
              + " (SeedRuntime) so the durable cellar is resolvable");
    }
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
}
