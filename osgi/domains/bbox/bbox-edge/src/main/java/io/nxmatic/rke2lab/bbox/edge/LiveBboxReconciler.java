package io.nxmatic.rke2lab.bbox.edge;

import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.reconcile.Action;
import io.nxmatic.bbox.reconcile.DesiredReservation;
import io.nxmatic.bbox.reconcile.ReservationReconciler;
import io.nxmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.nxmatic.bbox.reconcile.RowOutcome;
import io.nxmatic.rke2lab.bbox.contract.BboxAction;
import io.nxmatic.rke2lab.bbox.contract.BboxReconciler;
import io.nxmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.nxmatic.rke2lab.bbox.contract.BboxRowOutcome;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The realised bbox edge: opens one session to the Bouygues Bbox router via the embedded {@code
 * java-bbox-api-client}, hands it to the library's {@link ReservationReconciler} (which fetches the
 * reservation table once), and applies each desired reservation. The single door toward this one
 * external contact — the client and its gson dependency are nested in this bundle, so no library
 * type ever reaches the host; the seam speaks only the home vocabulary.
 */
@Component(service = BboxReconciler.class)
public final class LiveBboxReconciler implements BboxReconciler {

  @Override
  public List<BboxRowOutcome> reconcile(
      URI baseUri, String adminPassword, boolean dryRun, List<BboxReservationRequest> requests) {
    final Mode mode = dryRun ? Mode.DRY_RUN : Mode.APPLY;
    final List<BboxRowOutcome> outcomes = new ArrayList<>(requests.size());
    try (BboxApiClient client = openClient(baseUri, adminPassword)) {
      final ReservationReconciler reconciler = new ReservationReconciler(client);
      for (BboxReservationRequest request : requests) {
        final RowOutcome outcome =
            reconciler.apply(
                new DesiredReservation(request.mac(), request.ip(), request.hostname()), mode);
        outcomes.add(toHome(request, outcome));
      }
    }
    return outcomes;
  }

  private static BboxApiClient openClient(URI baseUri, String adminPassword) {
    try {
      return BboxApiClient.open(baseUri, adminPassword);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to open bbox session: " + ex.getMessage(), ex);
    }
  }

  private static BboxRowOutcome toHome(BboxReservationRequest request, RowOutcome outcome) {
    return new BboxRowOutcome(
        request.cluster(),
        request.node(),
        toHome(outcome.action()),
        outcome.mac(),
        outcome.ip(),
        outcome.hostname(),
        outcome.bboxId(),
        outcome.previousIp(),
        outcome.previousHostname(),
        outcome.failureMessage());
  }

  private static BboxAction toHome(Action action) {
    return switch (action) {
      case CREATED -> BboxAction.CREATED;
      case UPDATED -> BboxAction.UPDATED;
      case MATCHING -> BboxAction.MATCHING;
      case WOULD_CREATE -> BboxAction.WOULD_CREATE;
      case WOULD_UPDATE -> BboxAction.WOULD_UPDATE;
      case EXTRA -> BboxAction.EXTRA;
      case IGNORED -> BboxAction.IGNORED;
      case FAILED -> BboxAction.FAILED;
    };
  }
}
