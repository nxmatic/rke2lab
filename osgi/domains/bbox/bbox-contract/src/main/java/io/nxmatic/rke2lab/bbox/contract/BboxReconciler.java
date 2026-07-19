package io.nxmatic.rke2lab.bbox.contract;

import java.net.URI;
import java.util.List;

/**
 * The bbox domain's contact: reconcile a set of desired DHCP reservations against the Bouygues Bbox
 * router. The scion composes it MODE-BLIND — it enumerates the desired rows from the blueprint and
 * drives the reconciler resolved from the registry, with no notion of live vs preview.
 *
 * <p>The mode lives at the FRONTIER, not in this contract: bbox-edge provides a PAIR of impls, a
 * cultivating one (opens one session to the router via the embedded {@code java-bbox-api-client},
 * diffs the live table once and APPLIES each row) and a surveying one (contacts the router zero
 * times and projects the honest {@code WOULD_CREATE} upper bound). The {@code @OsgiService} bridge
 * reads the ambient {@link io.nxmatic.rke2lab.seed.broker.port.RunGate RunGate} once and resolves
 * the matching impl by LDAP filter on {@code rke2lab.gardening} — so no {@code dryRun} boolean is
 * ever passed in. The outcomes come back in request order, each carrying its {@code (cluster,
 * node)} identity so the caller correlates without re-matching.
 *
 * <p>A consumer-side service interface — so it lives in {@code bbox-contract} (type=contract,
 * installed + wired bundle-to-bundle) alongside the flat reservation vocabulary it speaks. Both the
 * edge that provides it and the scion that resolves it play in-container, so it never crosses to
 * the host: no seam.
 */
public interface BboxReconciler {

  /**
   * Reconcile {@code requests} against the router at {@code baseUri} authenticated by {@code
   * adminPassword}. Returns one {@link BboxRowOutcome} per request. Throws only on a session-level
   * failure (cannot open, auth rejected); per-row failures surface as {@link BboxAction#FAILED}
   * outcomes, not exceptions.
   */
  List<BboxRowOutcome> reconcile(
      URI baseUri, String adminPassword, List<BboxReservationRequest> requests);
}
