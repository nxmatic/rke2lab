package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import io.nxmatic.rke2lab.osgi.boot.discovery.BootPlan;
import java.util.Objects;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * The SHARED work of the discipline extensions (spec Figure 6): reach the OSGi world and raise the
 * start-level cursor to the bundle level, both bound to the class {@code Store} so the strategies
 * read them and the connection is released when the class scope ends. The two disciplines ({@link
 * IsolatedWorld}, {@link SeedRuntime}) extend this and differ ONLY in teardown.
 *
 * <p>The world is an ENTITY the base resolves, not a boot it hardcodes. It reads an {@link
 * OsgiConnection} from the class {@code Store} ({@link #CONNECTION}) if a modeled owner placed one
 * there — the socle's own tests do, over a Felix their {@code @RegisterExtension} fixture booted;
 * the connection then reports {@code ownsLifecycle() == false}, so the fixture keeps the teardown.
 * Absent, the base opens {@link OsgiConnection#embedded()} itself (the prod boot the real seed uses
 * in increment 2) and OWNS it, closing it at {@code afterAll}. The {@code ownsLifecycle} flag on
 * the connection — not a separate policy — decides who closes; there is no ambient supplier masking
 * the source.
 */
public abstract class BaseWorldExtension
    implements BeforeAllCallback, AfterAllCallback, TestInstancePostProcessor {

  /** Namespace of the socle's per-scope resources (the connection + the lever). */
  public static final Namespace NAMESPACE = Namespace.create(BaseWorldExtension.class);

  /**
   * Class-{@code Store} key for the {@link OsgiConnection}. A modeled owner (a test's boot fixture)
   * may place a non-owning connection here before the scope opens; absent, the base opens {@link
   * OsgiConnection#embedded()} and owns it.
   */
  public static final String CONNECTION = "connection";

  private static final String LEVER = "lever";

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    final Store store = context.getStore(NAMESPACE);

    OsgiConnection connection = store.get(CONNECTION, OsgiConnection.class);
    if (connection == null) {
      // The two boot knobs the launcher may have seeded: the framework log LEVEL and its FILE. Both
      // absent (the socle's own tests, an operator who set neither) ⇒ the Felix defaults.
      connection = OsgiConnection.embedded(LogLevelSeed.read(context), LogFileSeed.read(context));
      store.put(CONNECTION, connection);
    }

    final StartLevelLever lever = new StartLevelLever(connection.context());
    lever.raiseTo(BootPlan.START_LEVEL_BUNDLES);
    store.put(LEVER, lever);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    final OsgiConnection connection = connection(context);
    if (connection.ownsLifecycle()) {
      connection.close();
    }
  }

  /**
   * Bridge the class-scope connection to the scenario body: a scenario that {@link
   * ConnectionReceiver receives} it does {@code Gardening.over(connection)} instead of booting a
   * second Felix. Runs after {@link #beforeAll} placed the connection, before the body.
   */
  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    if (testInstance instanceof ConnectionReceiver receiver) {
      receiver.receiveConnection(connection(context));
    }
  }

  /** The lever piloting this scope's world — the teardown strategies move it. */
  protected final StartLevelLever lever(ExtensionContext context) {
    return Objects.requireNonNull(
        context.getStore(NAMESPACE).get(LEVER, StartLevelLever.class),
        "lever absent — beforeAll has not run");
  }

  /** The connection opened for this scope. */
  protected final OsgiConnection connection(ExtensionContext context) {
    return Objects.requireNonNull(
        context.getStore(NAMESPACE).get(CONNECTION, OsgiConnection.class),
        "connection absent — beforeAll has not run");
  }
}
