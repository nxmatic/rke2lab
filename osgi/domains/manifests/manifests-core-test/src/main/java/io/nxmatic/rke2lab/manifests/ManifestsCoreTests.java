package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The host-specific entry point of this fragment, called reflectively by the bare-JVM harness
 * THROUGH the manifests-core host classloader (a fragment shares its host's loader). The only glue
 * this fragment writes around the generic {@link InContainerJUnitRunner}: it names this host's test
 * package, then delegates. Authored in {@code io.nxmatic.rke2lab.manifests} so the in-container run
 * sees manifests-core's package-private actors white-box and reaches its DS @Components through the
 * host's {@code BundleContext}.
 *
 * <p>Invoked through the host loader, so this class's loader IS the host bundle's — the {@code
 * BundleReference} the runner enumerates the wiring from, and the loader it instantiates the
 * Jupiter engine through.
 */
public final class ManifestsCoreTests {

  private ManifestsCoreTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            ManifestsCoreTests.class.getClassLoader(),
            JupiterTestEngine.class,
            ManifestsCoreTests.class.getPackageName())
        .run();
  }
}
