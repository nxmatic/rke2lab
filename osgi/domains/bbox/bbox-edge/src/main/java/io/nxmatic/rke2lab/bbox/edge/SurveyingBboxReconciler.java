package io.nxmatic.rke2lab.bbox.edge;

import io.nxmatic.rke2lab.bbox.contract.BboxAction;
import io.nxmatic.rke2lab.bbox.contract.BboxReconciler;
import io.nxmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.nxmatic.rke2lab.bbox.contract.BboxRowOutcome;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.osgi.service.component.annotations.Component;

/**
 * The SURVEYING bbox edge — contacts the router ZERO times. It projects, for each desired row, the
 * honest upper bound a survey can assert without reading the live table: {@code WOULD_CREATE} (no
 * bbox-side id, no previous state, no failure). A survey that cannot read the router cannot tell
 * CREATE from UPDATE/MATCHING, so it never fabricates a MATCHING — real production code that plans,
 * not a fake that pretends to observe.
 *
 * <p>One of the bbox reconciler PAIR: registered with {@code rke2lab.gardening=surveying} so the
 * {@code @OsgiService} frontier picks it when the ambient RunGate is surveying. Its twin, {@link
 * CultivatingBboxReconciler}, opens the session and applies. Neither knows the mode — the frontier
 * chose; this one simply surveys, and imports nothing of {@code io.nxmatic.bbox.*} because it opens
 * no client.
 */
@Component(service = BboxReconciler.class, property = "rke2lab.gardening=surveying")
public final class SurveyingBboxReconciler implements BboxReconciler {

  @Override
  public List<BboxRowOutcome> reconcile(
      URI baseUri, String adminPassword, List<BboxReservationRequest> requests) {
    return requests.stream().map(SurveyingBboxReconciler::wouldCreate).toList();
  }

  private static BboxRowOutcome wouldCreate(BboxReservationRequest request) {
    return new BboxRowOutcome(
        request.cluster(),
        request.node(),
        BboxAction.WOULD_CREATE,
        request.mac(),
        request.ip(),
        request.hostname(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
