package io.nxmatic.rke2lab.cluster.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.contract.ControllerRef;
import java.nio.file.Path;
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
 * test: the kubectl contact is stateless and swallows its failure (process error, non-zero exit, or
 * {@code kubectl} absent from PATH all collapse to the same raw fact), so a contact against an
 * unreachable cluster deterministically returns {@code false} — no flakiness, no real cluster
 * needed. An empty controller list is vacuously effective.
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
