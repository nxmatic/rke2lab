package io.seedmatic.rke2lab.systemd.bdd;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The fragment's in-container entry point, called reflectively by the bare-JVM {@code
 * SystemdBddInContainerTest} proxy THROUGH the systemd-bdd host classloader (a fragment shares its
 * host's loader). The only glue this fragment writes around the generic {@link
 * InContainerJUnitRunner}: it names this fragment's test package, then delegates. Authored in
 * {@code io.seedmatic.rke2lab.systemd.bdd} so the passenger it enumerates runs on the host bundle's
 * loader — the loader the scenario resolves its collaborators against, so a mock the passenger
 * registers is the same Class the scenario reads.
 *
 * <p>The twin of {@code ClusterBddTests}: invoked through the host loader, so this class's loader
 * IS the host bundle's — the {@code BundleReference} the runner enumerates the wiring from, and the
 * loader it instantiates the Jupiter engine through (which drives the jGiven scenario the passenger
 * plays through the front-door).
 */
public final class SystemdBddTests {

  private SystemdBddTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            SystemdBddTests.class.getClassLoader(),
            JupiterTestEngine.class,
            SystemdBddTests.class.getPackageName())
        .run();
  }
}
