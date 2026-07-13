package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.Objects;
import java.util.Optional;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The registry view a scenario resolves its collaborators through — its OWN bundle's service
 * registry, reached from the scenario class via {@link FrameworkUtil} (so the same code plays live
 * and in test, only who registers the service differs). Every scion carried a byte-identical {@code
 * bundleContext()}/{@code require}/{@code resolveDoctor} triad; this is that triad, once.
 *
 * <p><b>Why {@link #require} AWAITS.</b> A scion resolving a REAL SCR service (manifests'
 * synthesis) races the SCR extender: a delayed component publishes its service only after its
 * mandatory references bind, ASYNC to {@code bundle.start()}. A bare {@code getServiceReference}
 * can fire in that window and see nothing. So {@code require} waits on a {@link ServiceTracker}
 * (the primitive the host-side {@code awaitService} already wraps) up to {@link #AWAIT_MILLIS}. It
 * is harmless for the scions whose collaborator is a mock the passenger registers before the play:
 * {@code waitForService} returns immediately when the service is already there.
 *
 * <p><b>Why {@link #optional} does NOT await.</b> The doctor is legitimately absent (a world booted
 * without it), so awaiting would block the full timeout every doctor-less run for nothing — it
 * takes a snapshot, empty when none is published.
 */
public final class ScenarioRegistry {

  /** How long {@link #require} waits for SCR to publish a delayed component's service. */
  private static final long AWAIT_MILLIS = 5_000;

  private final BundleContext context;

  private ScenarioRegistry(BundleContext context) {
    this.context = context;
  }

  /**
   * The registry view of the bundle that loaded {@code scenario} — fails loud if the scenario is
   * not bundle-loaded, because it is meant to play in-container (a flat-classpath run has no
   * registry to resolve against).
   */
  public static ScenarioRegistry of(Object scenario) {
    final Bundle bundle =
        Objects.requireNonNull(
            FrameworkUtil.getBundle(scenario.getClass()),
            scenario.getClass().getSimpleName()
                + " is not bundle-loaded — the scenario must play in-container");
    return new ScenarioRegistry(bundle.getBundleContext());
  }

  /**
   * A required collaborator, AWAITING SCR to publish it. Throws with {@code absenceMessage} if none
   * appears within {@link #AWAIT_MILLIS} — a wiring bug (the live edge or a test mock must publish
   * one), not a runtime condition to guess a default for.
   */
  public <T> T require(Class<T> type, String absenceMessage) {
    final ServiceTracker<T, T> tracker = new ServiceTracker<>(context, type, null);
    tracker.open();
    try {
      return Objects.requireNonNull(tracker.waitForService(AWAIT_MILLIS), absenceMessage);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + type.getName(), ex);
    } finally {
      tracker.close();
    }
  }

  /**
   * An optional collaborator — a snapshot of the registry, empty when none is published (e.g. a
   * world without the doctor). Does not await: absence is a legitimate outcome, not a race.
   */
  public <T> Optional<T> optional(Class<T> type) {
    return Optional.ofNullable(context.getServiceReference(type)).map(this::service);
  }

  private <T> T service(ServiceReference<T> ref) {
    return context.getService(ref);
  }
}
