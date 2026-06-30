package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.junit.testkit.container.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The host-specific entry point of this fragment, called reflectively by the bare-JVM harness
 * THROUGH the doctor-port host classloader (a fragment shares its host's loader). It is the only
 * glue a fragment writes around the generic {@link InContainerJUnitRunner}: it names this host's
 * test package, then delegates. Authored in {@code io.nxmatic.rke2lab.doctor.port} so the
 * in-container run sees the value-type tests white-box.
 *
 * <p>Invoked through the host loader, so this class's loader IS the host bundle's — exactly the
 * {@code BundleReference} the runner enumerates the wiring from, and the loader it instantiates the
 * passed {@link JupiterTestEngine} through. Naming the engine class here is what makes bnd import
 * {@code org.junit.jupiter.engine} into this fragment (and so into the host).
 */
public final class DoctorPortTests {

  private DoctorPortTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            DoctorPortTests.class.getClassLoader(),
            JupiterTestEngine.class,
            DoctorPortTests.class.getPackageName())
        .run();
  }
}
