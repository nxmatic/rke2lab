package io.nxmatic.rke2lab.cluster.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves the cluster edge resolves and publishes as a real OSGi citizen: SCR activates the
 * {@code @Component} and publishes {@link ClusterReadinessContact} TYPED, the seam package
 * resolving single-exporter (no ClassCastException across the boundary). Simpler than the
 * dbus-systemd boot test — this edge embeds no jars, so there is no transport ServiceLoader to
 * prove; the proof is just that the contact resolves and behaves.
 *
 * <p>Out-of-container, extension-only, NOT an in-container fragment: the edge needs no white-box
 * access — {@link ClusterReadinessContact} is an EXPORTED cluster-port type, observed typed via
 * {@code awaitService}.
 *
 * <p>The behavioural proof is the failure mode, because no real cluster is reachable in a unit
 * test: the kubectl contact is stateless and swallows its failure (process error, non-zero exit, or
 * {@code kubectl} absent from PATH all collapse to the same raw fact), so a contact against an
 * unreachable cluster deterministically returns {@code false} — no flakiness, no real cluster
 * needed. An empty controller list is vacuously effective.
 */
@OsgiWorld
class ClusterEdgeBootTest {

  private static final String EDGE_FIXTURE = "(&(type=edge)(edge=cluster))";

  // SCR runs so the edge's @Component is published; the seam package is system-exported from ONE
  // place so awaitService(ClusterReadinessContact.class) returns the host's own class, castable.
  // The
  // edge is installed by what it DECLARES (its embed capability), never by a symbolic-name literal.
  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages(
              "io.nxmatic.rke2lab.cluster.port;version=1.0.0", "org.slf4j;version=2.0.0")
          .installMatching(EDGE_FIXTURE)
          .build();

  @Test
  void scrPublishesTheContactTyped() throws Exception {
    assertNotNull(
        felix.awaitService(ClusterReadinessContact.class, 5000),
        "SCR published ClusterReadinessContact — the embedded edge @Component activated and the seam"
            + " package resolved single-exporter (typed, no ClassCastException)");
  }

  @Test
  void contactAnswersAgainstAnUnreachableCluster() throws Exception {
    final ClusterReadinessContact contact = felix.awaitService(ClusterReadinessContact.class, 5000);
    assertNotNull(contact, "the contact must be published before we can exercise it");

    final Path bogusKubeconfig = Path.of("/nonexistent/cluster-edge-boot-test/kubeconfig");

    assertFalse(
        contact.isApiReady(bogusKubeconfig),
        "no cluster is reachable, so the API-readiness contact must return false");

    assertTrue(
        contact.areControllersEffective(bogusKubeconfig, List.of()),
        "an empty controller list is vacuously effective — no contact is even made");

    assertFalse(
        contact.areControllersEffective(
            bogusKubeconfig,
            List.of(new ControllerRef("deployment", "cilium-operator", "kube-system"))),
        "no cluster is reachable, so a required controller cannot be effective");
  }
}
