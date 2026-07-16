package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The channel carrying the run's transaction id across the launcher membrane — the twin of {@link
 * RunRoleSeed}, for AUDIT correlation (§ seed-broker-spec cellar-transactional). A sown scion opens
 * its OWN launcher session, so the root's session store does not reach it; the {@code
 * RunbookHandler} that plays the scion RELAYS the {@code txId} it received on {@code handle} by
 * seeding it here ({@link #into}), and {@code ScenarioCellarExtension} reads it ({@link #read}) to
 * post a narrative tag on the scion's runbook — which the graft folds up into the root tree, so a
 * fragment's work reads back as "transaction X" in the runbook.
 *
 * <p>Absent (a run with no correlation id) ⇒ empty: no tag posed.
 */
public final class TxIdSeed {

  private TxIdSeed() {}

  /** The store key both the relaying handler (seed) and the extension (read) address it by. */
  public static final String STORE_KEY = "tx-id";

  private static final String[] NS_PARTS = {TxIdSeed.class.getName(), STORE_KEY};

  /** The relaying consumer — put {@code txId} into the in-container session store. */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(String txId) {
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    return store -> store.put(ns, STORE_KEY, txId);
  }

  /** Read the relayed txId from the session store (via the parent chain); empty when none. */
  public static Optional<String> read(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    return Optional.ofNullable(context.getStore(ns).get(STORE_KEY, String.class));
  }
}
