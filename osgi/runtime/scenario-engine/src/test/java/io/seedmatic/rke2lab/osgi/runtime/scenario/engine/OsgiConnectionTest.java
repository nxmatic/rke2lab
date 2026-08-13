package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * Pins the {@link OsgiConnection} CONTRACT (spec Figure 3): a connection is a lifecycle handle over
 * a live {@code BundleContext} — {@code context()} / {@code ownsLifecycle()} / {@code close()} —
 * not a boot mechanism. The prod {@code embedded()} boots Felix from the staged bundles a deployed
 * exec-jar carries, so it is NOT exercised here (this library module stages none); increment 2's
 * seed exercises it for real. The socle proves the contract over a real Felix booted by the testkit
 * and wrapped with {@code over(...)}.
 */
@OsgiWorld
class OsgiConnectionTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder().build();

  @Test
  void ownedConnectionExposesTheLiveWorldAndRunsItsCloseAction() {
    final AtomicBoolean closed = new AtomicBoolean(false);
    final OsgiConnection connection =
        OsgiConnection.over(felix.context(), true, () -> closed.set(true));

    assertTrue(connection.ownsLifecycle(), "over(…, true, …) owns the lifecycle");
    assertNotNull(connection.context(), "the live world's BundleContext is reachable");
    assertEquals(
        Bundle.ACTIVE,
        connection.context().getBundle(0).getState(),
        "the system bundle (id 0) is ACTIVE — the world is live");

    connection.close();
    assertTrue(closed.get(), "close() runs the teardown action of an owned connection");
  }

  @Test
  void attachedConnectionOwnsNothing() {
    final OsgiConnection connection = OsgiConnection.over(felix.context(), false, () -> {});
    assertFalse(connection.ownsLifecycle(), "an attached connection owns no lifecycle");
  }

  @Test
  void remoteIsNamedButNotYetRealised() {
    final UnsupportedOperationException thrown =
        assertThrows(
            UnsupportedOperationException.class, () -> OsgiConnection.remote("tcp://nowhere"));
    assertTrue(
        thrown.getMessage().contains("tcp://nowhere"),
        "the failure names the endpoint that is not yet reachable");
  }
}
