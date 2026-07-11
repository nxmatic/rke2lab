package io.nxmatic.rke2lab.cluster.edge;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The fragment's in-container entry point, called reflectively by the bare-JVM {@code
 * ClusterEdgeBootTest} proxy THROUGH the cluster-edge host classloader (a fragment shares its
 * host's loader). The only glue this fragment writes around the generic {@link
 * InContainerJUnitRunner}: it names this fragment's test package, then delegates. Authored in
 * {@code io.nxmatic.rke2lab.cluster.edge} so the passenger it enumerates runs on the host bundle's
 * loader — the realm the edge's SCR-published ClusterReadinessContact lives in, so the passenger
 * resolves and calls it TYPED without cluster.contract being system-exported.
 */
public final class ClusterEdgeTests {

  private ClusterEdgeTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            ClusterEdgeTests.class.getClassLoader(),
            JupiterTestEngine.class,
            ClusterEdgeTests.class.getPackageName())
        .run();
  }
}
