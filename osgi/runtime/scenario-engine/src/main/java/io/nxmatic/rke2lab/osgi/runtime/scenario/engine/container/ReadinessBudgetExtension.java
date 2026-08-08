package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * Resolves a readiness scenario's effective {@link ReadinessBudget} and hands it to the scenario
 * before the body — the readiness twin of {@link ScenarioInputSeed}, part of the
 * {@code @SeedScenario} socle. It folds two sources: the {@link ReadinessDeadlines} the
 * {@code @Test} DECLARES (the visible-in-code defaults) and the {@link ReadinessDeadlineOverride}
 * the host seeds from the stack config — {@code effective = override.orElse(annotation-default)}
 * per deadline.
 *
 * <p>Both halves ride the same store the seeds use: the host's {@link #into} puts the override into
 * the launcher session store, and {@code beforeTestExecution} reads it back through the parent
 * chain (empty ⇒ {@link ReadinessDeadlineOverride#NONE}, all annotation). A live object, in-realm
 * (two JDK {@code Duration}s, no codec) — the same crossing the input rides.
 *
 * <p>Opt-in by implementing {@link ReadinessBudgetReceiver}; a scenario that does not is left
 * untouched. A receiver that omits {@link ReadinessDeadlines} on its {@code @Test} is a wiring bug
 * — the point of the annotation is the visible default — so this fails loud rather than inventing
 * one.
 */
public final class ReadinessBudgetExtension implements BeforeTestExecutionCallback {

  private static final String KEY = "readiness-deadline-override";

  private static String[] nsParts() {
    return new String[] {ReadinessBudgetExtension.class.getName(), KEY};
  }

  /**
   * The host's seeding consumer, handed to the launcher's session-store seed: put {@code override}
   * under this extension's namespace + key. {@code beforeTestExecution} reads exactly it back. The
   * host builds the override from Pulumi ({@code rke2lab:readiness:connectTimeout} / {@code
   * :timeout}); seeding nothing leaves every deadline at the annotation default.
   */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(
      ReadinessDeadlineOverride override) {
    final Namespace ns = Namespace.create((Object[]) nsParts());
    return store -> store.put(ns, KEY, override);
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    if (!(context.getRequiredTestInstance() instanceof ReadinessBudgetReceiver receiver)) {
      return;
    }
    final ReadinessDeadlines deadlines =
        context.getRequiredTestMethod().getAnnotation(ReadinessDeadlines.class);
    if (deadlines == null) {
      throw new IllegalStateException(
          context.getRequiredTestClass().getSimpleName()
              + " is a ReadinessBudgetReceiver but its @Test carries no @ReadinessDeadlines —"
              + " declare the connect/ready defaults where they read");
    }
    receiver.receiveBudget(resolve(deadlines, readOverride(context)));
  }

  private static ReadinessBudget resolve(
      ReadinessDeadlines deadlines, ReadinessDeadlineOverride override) {
    final Duration connect =
        override.connect().orElseGet(() -> Duration.parse(deadlines.connect()));
    final Duration ready = override.ready().orElseGet(() -> Duration.parse(deadlines.ready()));
    return ReadinessBudget.of(connect, ready);
  }

  private static ReadinessDeadlineOverride readOverride(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) nsParts());
    return Optional.ofNullable(context.getStore(ns).get(KEY, ReadinessDeadlineOverride.class))
        .orElse(ReadinessDeadlineOverride.NONE);
  }
}
