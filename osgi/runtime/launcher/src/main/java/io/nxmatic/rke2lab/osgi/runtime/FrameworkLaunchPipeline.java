package io.nxmatic.rke2lab.osgi.runtime;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootPlan;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootPlanner;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootRequest;
import io.nxmatic.rke2lab.osgi.boot.discovery.HostClassLoaderView;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * The embedded-OSGi boot expressed in the project's fluent-pipeline grammar — three topics in a
 * fixed, non-reorderable order:
 *
 * <pre>
 *   discovery  →  plan  →  launch
 *   (PURE: choose the    (PURE: BootPlanner     (EFFECT: FrameworkLauncher
 *    index + policy)      decides the BootPlan)   enacts it → BootedFramework)
 * </pre>
 *
 * <p>The state boundary is the plan/launch seam: you cannot launch without a plan, and the pure
 * plan can be inspected at {@code PlanDone} without booting Felix. This is the executor counterpart
 * of the boot MODEL in {@code osgi/boot/boot-discovery}; the test harness ({@code
 * OutOfContainerFrameworkExtension}) is the other one, sharing {@link BootPlanner} + {@link
 * FrameworkLauncher}.
 *
 * <p>Exec entrypoints do not spell the three topics out — they call {@link #embedded()}, a preset
 * that pre-fills the fixed prod topology (the staged index, install-everything) and exposes only
 * the TAIL: {@code .during(topic, framework -> …)} (read several services) or {@code .during(topic,
 * Service.class, svc -> …)} (resolve one). The preset owns the boot-run-close lifecycle.
 */
public final class FrameworkLaunchPipeline {

  /** How long the single-service tail waits for SCR to publish the service before failing fast. */
  private static final long SERVICE_TIMEOUT_MILLIS = 5000;

  private FrameworkLaunchPipeline() {}

  /**
   * The prod preset: boot the embedded stack staged under {@code META-INF/bundles/} (the staged
   * index, {@code DiscoveryPolicy.all()}), then run a tail with the booted framework and close it —
   * always, even if the tail throws. Fails fast if the artifact carries no embedded bundles (a
   * packaging defect, not a degraded run mode). The shape every exec entrypoint uses.
   */
  public static Embedded embedded() {
    return new Embedded();
  }

  /**
   * The boot-run-close preset over the fixed embedded topology; choose the tail with {@code
   * during}.
   */
  public static final class Embedded {

    private OnFailure onFailure = OnFailure.noop();

    private Embedded() {}

    /**
     * Optional per-topic failure handler; a boot failure wraps as {@code TopicFailure("boot", …)}.
     */
    public Embedded onFailure(OnFailure handler) {
      this.onFailure = handler;
      return this;
    }

    /**
     * Boot, run {@code tail} with the booted {@link BootedFramework}, then close — for an
     * entrypoint whose tail reads several services from the registry (seed-master's bootstrap
     * pipeline). The boot-run-close shape: the lifecycle is owned here.
     */
    public void during(String topic, Consumer<BootedFramework> tail) {
      new FluentTopicRunner("boot")
          .runDuring(
              topic,
              this,
              self -> {
                try (BootedFramework framework = launch()) {
                  tail.accept(framework);
                }
                return self;
              },
              onFailure);
    }

    /**
     * Boot and HAND the live {@link BootedFramework} OUT — the caller owns the lifecycle (closes it
     * itself). The shape a test needs when it boots once and reads the registry from several
     * {@code @Test} methods (e.g. {@code try (var f = FrameworkLaunchPipeline.embedded().launch())
     * { … }}); the embedded prod topology is exercised exactly as a deployed exec-jar would. Fails
     * fast if the artifact carries no embedded bundles (a packaging defect, not a degraded run
     * mode).
     */
    public BootedFramework launch() {
      if (!hasEmbeddedBundles()) {
        throw new IllegalStateException(
            "exec-jar assembled without its embedded OSGi bundles under META-INF/bundles/");
      }
      try {
        return bootEmbedded();
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to boot the embedded OSGi runtime", ex);
      }
    }

    /**
     * Boot, resolve exactly one {@code service} from the registry (fail-fast if SCR never publishes
     * it), run {@code tail} with it, then close — the single-service shape a CLI north-adapter
     * needs.
     */
    public <T> void during(String topic, Class<T> service, Consumer<T> tail) {
      during(
          topic,
          framework -> {
            final T resolved = framework.awaitService(service, SERVICE_TIMEOUT_MILLIS);
            if (resolved == null) {
              throw new IllegalStateException(
                  "embedded bundle booted but SCR never published " + service.getName());
            }
            tail.accept(resolved);
          });
    }

    private static BootedFramework bootEmbedded() throws IOException {
      final BootPlan plan =
          new BootPlanner(HOST.stagedBundles(), HOST::resolves)
              .plan(BootRequest.create().embedBootStack());
      return new FrameworkLauncher(LaunchConfig.defaults()).launch(plan, true);
    }
  }

  /**
   * This exec-jar's host-world view (the flat JCL the boot runs on) — the one collaborator the
   * staged-bundle index and the host-resolution predicate both derive from, passed into {@link
   * BootPlanner} rather than reached through static helpers.
   */
  private static final HostClassLoaderView HOST =
      HostClassLoaderView.of(FrameworkLaunchPipeline.class.getClassLoader());

  /**
   * Whether the running process carries the embedded boot stack — true in a deployed exec-jar,
   * false on a reactor/test classpath. Probes felix.scr (by its symbolic name), the boot-stack
   * bundle common to every entrypoint.
   */
  public static boolean hasEmbeddedBundles() {
    return HOST.stagedBundles().contains(BootStackJar.FELIX_SCR.symbolicName());
  }
}
