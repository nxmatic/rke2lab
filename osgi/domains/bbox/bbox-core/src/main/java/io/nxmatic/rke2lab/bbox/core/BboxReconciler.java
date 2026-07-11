package io.nxmatic.rke2lab.bbox.core;

import java.net.URI;
import java.util.List;

/**
 * The bbox domain's contact: reconcile a set of desired DHCP reservations against the Bouygues Bbox
 * router. The bbox-edge provides it by opening one session to the router (it embeds {@code
 * java-bbox-api-client}) and applying each row; the scion composes it, enumerating the desired rows
 * from the blueprint and driving the reconciler resolved from the registry.
 *
 * <p>One call, one session: the edge fetches the router's reservation table once and diffs every
 * request against it. {@code dryRun} maps to the library's DRY_RUN mode (no writes — matches {@code
 * pulumi preview}); otherwise APPLY. The scion derives {@code dryRun} from the ambient RunGate
 * ({@code dryRun = !cultivating()}). The outcomes come back in request order, each carrying its
 * {@code (cluster, node)} identity so the caller correlates without re-matching.
 *
 * <p>A service interface, not data — so it lives in {@code bbox-core} (type=model, installed +
 * wired bundle-to-bundle), not {@code bbox-record}. Both the edge that provides it and the scion
 * that resolves it play in-container, so it never crosses to the host: no seam.
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
