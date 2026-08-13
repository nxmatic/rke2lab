package io.seedmatic.rke2lab.doctor.contract;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The host-specific entry point of this fragment, called reflectively by the bare-JVM harness
 * THROUGH the doctor-records host classloader (a fragment shares its host's loader). The only glue
 * this fragment writes around the generic {@link InContainerJUnitRunner}: it names this host's test
 * package, then delegates. Authored in {@code io.seedmatic.rke2lab.doctor.contract} so the
 * in-container run sees the record value-type tests white-box.
 *
 * <p>Invoked through the host loader, so this class's loader IS the host bundle's — the {@code
 * BundleReference} the runner enumerates the wiring from, and the loader it instantiates the passed
 * {@link JupiterTestEngine} through. Replaces {@code DoctorPortTests} (doctor-port is gone).
 */
public final class DoctorContractTests {

  private DoctorContractTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            DoctorContractTests.class.getClassLoader(),
            JupiterTestEngine.class,
            DoctorContractTests.class.getPackageName())
        .run();
  }
}
