package io.nxmatic.rke2lab.bbox.port;

import java.net.URI;
import java.util.List;

/**
 * The bbox domain's external-contact seam: reconcile a set of desired DHCP reservations against the
 * Bouygues Bbox router. The bbox-edge provides it by opening one session to the router (it embeds
 * {@code java-bbox-api-client}) and applying each row; the host composes it, keeping the blueprint
 * enumeration and the Pulumi resource-graph projection.
 *
 * <p>One call, one session: the edge fetches the router's reservation table once and diffs every
 * request against it. {@code dryRun} maps to the library's DRY_RUN mode (no writes — matches {@code
 * pulumi preview}); otherwise APPLY. The outcomes come back in request order, each carrying its
 * {@code (cluster, node)} identity so the host correlates without re-matching.
 */
public interface BboxReconciler {

  /**
   * Reconcile {@code requests} against the router at {@code baseUri} authenticated by {@code
   * adminPassword}. Returns one {@link BboxRowOutcome} per request. Throws only on a session-level
   * failure (cannot open, auth rejected); per-row failures surface as {@link BboxAction#FAILED}
   * outcomes, not exceptions.
   */
  List<BboxRowOutcome> reconcile(
      URI baseUri, String adminPassword, boolean dryRun, List<BboxReservationRequest> requests);
}
