package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The host-specific entry point of this fragment, called reflectively by the bare-JVM harness
 * THROUGH the incus-core host classloader (a fragment shares its host's loader). The only glue this
 * fragment writes around the generic {@link InContainerJUnitRunner}: it names this host's test
 * package, then delegates. Authored in {@code io.seedmatic.rke2lab.incus.core} so the in-container
 * run reaches incus-core's @Components through the host's {@code BundleContext}. (An in-container
 * harness kept for incus-core cross-bundle proofs; it currently carries no test class of its own.)
 */
public final class IncusCoreTests {

  private IncusCoreTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            IncusCoreTests.class.getClassLoader(),
            JupiterTestEngine.class,
            IncusCoreTests.class.getPackageName())
        .run();
  }
}
