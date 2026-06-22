package io.nxmatic.rke2lab.osgi.runtime;

import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * The common boot prefix every exec entrypoint (seed) shares: boot the embedded Felix from the
 * bundles staged in this exec-jar, run a workload against the booted registry, then close the
 * framework — always, even if the workload throws. The startup is identical across seeds; only the
 * TAIL (what each seed does with the registry) differs, so the tail is a contributed callback.
 *
 * <p>The three entrypoints are three specializations of this shape:
 *
 * <ul>
 *   <li>seed-master boots, then runs its full {@code BootstrapPipeline} as the tail — {@link
 *       Booting#during(String, Consumer)}, which hands the booted {@link OsgiRuntime} over;
 *   <li>a CLI boots, resolves ONE {@code -port} service, and drives it — {@link
 *       Booting#during(String, Class, Consumer)}, the fail-fast typed resolve.
 * </ul>
 *
 * <p>The boot-run-close lifecycle is wrapped as a single pipeline topic via {@link
 * FluentTopicRunner} (the {@code topic} label drives logging + a {@code TopicFailure} on failure),
 * so the seam reads like the rest of the fluent-pipeline grammar. Host-only by nature — it LAUNCHES
 * the framework, so it cannot live in a bundle (a bundle cannot boot the framework it runs in).
 */
public final class SeedRuntime {

  /** How long a CLI waits for SCR to publish the service it resolves before failing fast. */
  private static final long SERVICE_TIMEOUT_MILLIS = 5000;

  private SeedRuntime() {}

  /**
   * Begin a seed boot over {@link OsgiRuntime#embeddedBootStack()}. The model bundle(s) to install
   * are not named here: {@link OsgiRuntime#boot()} DISCOVERS them by scanning {@link
   * OsgiRuntime#EMBEDDED_BUNDLES_ROOT} for the embed capability, so an entrypoint declares only its
   * staged jars (the pom) — never a name in Java. Choose the tail shape with {@code during(...)}.
   */
  public static Booting bootingEmbedded() {
    return new Booting();
  }

  /** The tail shape is chosen via {@code during(...)}; the model bundles are scanned at boot. */
  public static final class Booting {

    private Booting() {}

    /**
     * Boot the framework, run {@code tail} with the booted {@link OsgiRuntime}, then close it. For
     * an entrypoint whose tail reads several services from the registry (seed-master's bootstrap
     * pipeline). Fails fast if the artifact carries no embedded bundles — a packaging defect, not a
     * degraded run mode.
     */
    public void during(String topic, Consumer<OsgiRuntime> tail) {
      FluentTopicRunner.runDuring(
          "seed",
          topic,
          this,
          booting -> {
            if (!OsgiRuntime.hasEmbeddedBundles()) {
              throw new IllegalStateException(
                  "exec-jar assembled without its embedded OSGi bundles under META-INF/bundles/");
            }
            try (OsgiRuntime runtime = boot()) {
              tail.accept(runtime);
            } catch (IOException ex) {
              throw new UncheckedIOException("failed to boot the embedded OSGi runtime", ex);
            }
            return booting;
          },
          null);
    }

    /**
     * Boot the framework, resolve exactly one {@code service} from the registry (fail-fast if SCR
     * never publishes it), run {@code tail} with it, then close. The single-service shape a CLI
     * north-adapter needs — its whole job is to drive one port service.
     */
    public <T> void during(String topic, Class<T> service, Consumer<T> tail) {
      during(
          topic,
          runtime -> {
            final T resolved = runtime.awaitService(service, SERVICE_TIMEOUT_MILLIS);
            if (resolved == null) {
              throw new IllegalStateException(
                  "embedded bundle booted but SCR never published " + service.getName());
            }
            tail.accept(resolved);
          });
    }

    private OsgiRuntime boot() throws IOException {
      return OsgiRuntime.embeddedBootStack().build().boot();
    }
  }
}
