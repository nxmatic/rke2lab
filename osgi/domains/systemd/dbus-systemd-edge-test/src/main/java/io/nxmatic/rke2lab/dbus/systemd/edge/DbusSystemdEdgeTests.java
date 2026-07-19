package io.nxmatic.rke2lab.dbus.systemd.edge;

import io.nxmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;

/**
 * The fragment's in-container entry point, called reflectively by the bare-JVM {@code
 * DbusSystemdEdgeBootTest} proxy THROUGH the dbus-systemd-edge host classloader (a fragment shares
 * its host's loader). The only glue this fragment writes around the generic {@link
 * InContainerJUnitRunner}: it names this fragment's test package, then delegates. Authored in
 * {@code io.nxmatic.rke2lab.dbus.systemd.edge} so the passenger it enumerates runs on the host
 * bundle's loader — the realm the edge's SCR-published SystemdRuntimeProbe lives in, so the
 * passenger resolves and calls probe() TYPED (running the dbus-java transport ServiceLoader inside
 * the edge's own Bundle-ClassPath) without systemd.contract being system-exported.
 */
public final class DbusSystemdEdgeTests {

  private DbusSystemdEdgeTests() {}

  /**
   * @return one encoded result line per finished test (see {@link InContainerJUnitRunner}).
   */
  public static List<String> run() throws InterruptedException {
    return new InContainerJUnitRunner<>(
            DbusSystemdEdgeTests.class.getClassLoader(),
            JupiterTestEngine.class,
            DbusSystemdEdgeTests.class.getPackageName())
        .run();
  }
}
