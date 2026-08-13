package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.log.LogLevel;

/**
 * The channel carrying the framework {@link LogLevel} across the launcher membrane — the twin of
 * {@link io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed RunRoleSeed},
 * minus the receiver hop: whoever LAUNCHES seeds the operator's {@code logging:level} knob into the
 * session store ({@link #into}), and {@link BaseWorldExtension} reads it back ({@link #read}) to
 * raise the embedded framework's own verbosity before booting Felix (so a failed resolve explains
 * itself). The two namespaces (launcher-side {@link Namespace}, Jupiter-side {@link
 * ExtensionContext.Namespace}) carry the SAME parts so the store lookup crosses the parent chain up
 * to the seeded session store.
 *
 * <p>Lives in the host-side seam package (never wired in-container): the level is a
 * boot-CONSTRUCTION parameter, consumed on the flat host classpath before the framework exists — no
 * in-container code ever reads it, which is why the bundle's {@code org.osgi.service.log} import
 * stays optional.
 *
 * <p>Absent (no launcher seeded one — the two CLIs, or an operator who set no knob) ⇒ {@link
 * Optional#empty()}: the caller boots with the Felix default via {@link OsgiConnection#embedded()}.
 */
public final class LogLevelSeed {

  private LogLevelSeed() {}

  private static final String KEY = "framework-log-level";
  private static final String[] NS_PARTS = {LogLevelSeed.class.getName(), KEY};

  /**
   * The launcher's seeding consumer — put {@code level} into the session store under the channel.
   */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(LogLevel level) {
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    return store -> store.put(ns, KEY, level);
  }

  /** Read the seeded level from the session store (via the parent chain); empty when none. */
  public static Optional<LogLevel> read(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    return Optional.ofNullable(context.getStore(ns).get(KEY, LogLevel.class));
  }
}
