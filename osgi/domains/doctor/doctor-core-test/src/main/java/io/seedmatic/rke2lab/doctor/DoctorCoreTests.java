package io.seedmatic.rke2lab.doctor;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The host-specific entry point of this fragment, called reflectively by the bare-JVM harness
 * THROUGH the doctor-core host classloader (a fragment shares its host's loader). The only glue
 * this fragment writes around the generic {@link InContainerJUnitRunner}: it names this host's test
 * package, then delegates. Authored in {@code io.seedmatic.rke2lab.doctor} so the in-container run
 * sees doctor-core's sealed package-private actors white-box.
 *
 * <p>Invoked through the host loader, so this class's loader IS the host bundle's — the {@code
 * BundleReference} the runner enumerates the wiring from, and the loader it instantiates the
 * Jupiter engine through (which also drives the jGiven scenarios, since {@code ScenarioTest} is a
 * Jupiter test).
 */
public final class DoctorCoreTests {

  private DoctorCoreTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            DoctorCoreTests.class.getClassLoader(),
            JupiterTestEngine.class,
            DoctorCoreTests.class.getPackageName())
        .run();
  }
}
