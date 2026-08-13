package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The channel carrying the {@link RunRole} across the launcher membrane — the twin of {@code
 * SessionSeed}, minus the receiver hop: whoever LAUNCHES seeds a role into the session store
 * ({@link #into}), and {@code ScenarioCellarExtension} reads it back ({@link #read}) to decide
 * whether it drains. The two namespaces (launcher-side {@link Namespace}, Jupiter-side {@link
 * ExtensionContext.Namespace}) carry the SAME parts so the store lookup crosses the parent chain up
 * to the seeded session store.
 *
 * <p>Absent (no launcher seeded one) ⇒ {@link RunRole#FRAGMENT}: the conservative default, a scion
 * that no one declared root does not drain.
 */
public final class RunRoleSeed {

  private RunRoleSeed() {}

  private static final String[] NS_PARTS = {RunRoleSeed.class.getName(), RunRole.STORE_KEY};

  /**
   * The launcher's seeding consumer — put {@code role} into the session store under the channel.
   */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(RunRole role) {
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    return store -> store.put(ns, RunRole.STORE_KEY, role);
  }

  /** Read the seeded role from the session store (via the parent chain); FRAGMENT when none. */
  public static RunRole read(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    final RunRole role = context.getStore(ns).get(RunRole.STORE_KEY, RunRole.class);
    return role == null ? RunRole.FRAGMENT : role;
  }
}
