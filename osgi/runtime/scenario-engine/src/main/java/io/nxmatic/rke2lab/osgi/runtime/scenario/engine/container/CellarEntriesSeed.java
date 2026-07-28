package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The channel carrying a transaction's accumulated CELLAR ENTRIES across the launcher membrane —
 * the twin of {@link TxIdSeed}, the ALLER sense of the transaction (§ seed-broker-spec, the entries
 * descend). A sown scion opens its OWN launcher session, so the root's session store does not reach
 * it; the {@code RunbookHandler} that plays the scion RELAYS the entries it read off the parent
 * cellar by seeding them here ({@link #into}), and {@code ScenarioCellarExtension} reads them
 * ({@link #read}) to re-post them onto the scion's model (via {@code
 * ScenarioCellar.inheritEntries}) so the scion reads its parent's in-flight stores as its own
 * overlay (read-your-parent's-writes).
 *
 * <p>What crosses is FLAT: the entries are {@code ScenarioCellar.entriesEncoded(sownChild)} — a
 * {@code List<String>} of already-encoded entries, the run-provenance path among them extended with
 * the sown child's crossing crumb — serialised to ONE JSON String (the isolation guard-rail: no
 * live {@code ScenarioCellar}/{@code Entry} crosses the dual-realm membrane, only strings). Absent
 * (a run with no parent transaction) ⇒ empty list: nothing inherited.
 */
public final class CellarEntriesSeed {

  private CellarEntriesSeed() {}

  /** The store key both the relaying handler (seed) and the extension (read) address it by. */
  public static final String STORE_KEY = "cellar-entries";

  private static final String[] NS_PARTS = {CellarEntriesSeed.class.getName(), STORE_KEY};

  private static final SeedCodec CODEC = new SeedCodec();

  /**
   * The relaying consumer — serialise the encoded-entry list to one JSON String and put it into the
   * in-container session store. An empty list seeds nothing (no channel opened).
   */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(List<String> entries) {
    if (entries.isEmpty()) {
      return store -> {};
    }
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    final String encoded = CODEC.encode(entries);
    return store -> store.put(ns, STORE_KEY, encoded);
  }

  /**
   * Read the relayed entries from the session store (via the parent chain) as the encoded-entry
   * list; empty when none was seeded.
   */
  @SuppressWarnings("unchecked")
  public static List<String> read(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    final String encoded = context.getStore(ns).get(STORE_KEY, String.class);
    return encoded == null ? List.of() : CODEC.decode(encoded, List.class);
  }
}
