package io.seedmatic.rke2lab.cluster.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessSnapshot;
import io.seedmatic.rke2lab.cluster.contract.ControllerRef;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The in-container proof of the cluster edge, run WHERE the edge lives (this passenger shares the
 * cluster-edge host loader through the fragment). It resolves the SCR-published {@link
 * ClusterReadinessContact} from the edge's OWN registry and calls it TYPED — so {@code
 * cluster.contract} need NOT be system-exported (which would legitimize the host reading a
 * de-seamed contract typed in the live boot). The test posture matches the live posture:
 * in-container, wired bundle-to-bundle.
 *
 * <p>The behavioural proof is the failure mode, because no real cluster is reachable in a unit
 * test: the fabric8 contact is stateless and swallows its failure (an unreadable kubeconfig, a
 * refused connection, or a non-2xx {@code /readyz} all collapse to the same raw fact), so a contact
 * against an unreachable cluster deterministically returns {@code false} — no flakiness, no real
 * cluster needed. An empty controller list is vacuously effective.
 */
public class ClusterEdgeContactInContainerTest {

  @Test
  void scr_publishes_the_contact() {
    assertNotNull(
        resolveContact(),
        "SCR published ClusterReadinessContact — the edge @Component activated in-container");
  }

  @Test
  void contact_answers_against_an_unreachable_cluster() {
    final ClusterReadinessContact contact = resolveContact();
    assertNotNull(contact, "the contact must be published before we can exercise it");

    final Path bogusKubeconfig = Path.of("/nonexistent/cluster-edge-boot-test/kubeconfig");

    // A TINY connect budget: the reach retries a few times over ~800ms then gives up — the edge
    // converts that to a not-ready snapshot rather than hanging on the default 2-minute deadline.
    final ReadinessBudget budget =
        new ReadinessBudget(Duration.ofMillis(200), Duration.ofMillis(800), Duration.ofMillis(200));

    final ClusterReadinessSnapshot snapshot =
        contact.awaitReady(
            bogusKubeconfig,
            List.of(new ControllerRef("deployment", "cilium-operator", "kube-system")),
            budget);

    assertFalse(
        snapshot.apiReady(),
        "no cluster is reachable, so the kube-apiserver never becomes ready within the budget");
    assertFalse(
        snapshot.controllersEffective(),
        "with no reachable API, a required controller cannot be effective");
  }

  /**
   * Resolve the edge's SCR-published contact from THIS bundle's own registry (same realm), bounded
   * — SCR activation is asynchronous after the edge bundle starts. Uses only {@code
   * org.osgi.framework}, resolvable in-container.
   */
  private static ClusterReadinessContact resolveContact() {
    final BundleContext context =
        FrameworkUtil.getBundle(ClusterEdgeTests.class).getBundleContext();
    final long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      final ServiceReference<ClusterReadinessContact> ref =
          context.getServiceReference(ClusterReadinessContact.class);
      if (ref != null) {
        final ClusterReadinessContact contact = context.getService(ref);
        if (contact != null) {
          return contact;
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }
}
