package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The channel carrying the framework boot log's FILE across the launcher membrane — the twin of
 * {@link LogLevelSeed}, for the other boot knob: whoever LAUNCHES seeds the relative path this
 * exec's framework log should write to ({@link #into}), and {@link BaseWorldExtension} reads it
 * back ({@link #read}) to point the embedded boot at its own file before booting Felix — so each
 * exec-jar keeps a distinct trace ({@code .local.d/manifests-cli.log}, {@code
 * .local.d/netplan-cli.log}) rather than all clobbering the shared {@link
 * io.seedmatic.rke2lab.osgi.runtime.framework.LaunchConfig#DEFAULT_LOG_FILE}.
 *
 * <p>Like the level, the file is a boot-CONSTRUCTION parameter consumed on the flat host classpath
 * before the framework exists; no in-container code reads it. Absent ⇒ {@link Optional#empty()}:
 * the boot keeps the default log file.
 */
public final class LogFileSeed {

  private LogFileSeed() {}

  private static final String KEY = "framework-log-file";
  private static final String[] NS_PARTS = {LogFileSeed.class.getName(), KEY};

  /** The launcher's seeding consumer — put {@code logFile} into the session store under the key. */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(String logFile) {
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    return store -> store.put(ns, KEY, logFile);
  }

  /** Read the seeded log file from the session store (via the parent chain); empty when none. */
  public static Optional<String> read(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    return Optional.ofNullable(context.getStore(ns).get(KEY, String.class));
  }
}
