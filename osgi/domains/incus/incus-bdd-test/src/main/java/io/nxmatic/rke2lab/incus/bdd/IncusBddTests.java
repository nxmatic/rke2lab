package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The fragment's in-container entry point, called reflectively by the bare-JVM {@code
 * IncusBddInContainerTest} proxy THROUGH the incus-bdd host classloader (a fragment shares its
 * host's loader). The only glue this fragment writes around the generic {@link
 * InContainerJUnitRunner}: it names this fragment's test package, then delegates. Authored in
 * {@code io.nxmatic.rke2lab.incus.bdd} so the passenger it enumerates runs on the host bundle's
 * loader — the loader the scenario resolves its collaborators against, so a mock the passenger
 * registers is the same Class the scenario reads.
 */
public final class IncusBddTests {

  private IncusBddTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            IncusBddTests.class.getClassLoader(),
            JupiterTestEngine.class,
            IncusBddTests.class.getPackageName())
        .run();
  }
}
