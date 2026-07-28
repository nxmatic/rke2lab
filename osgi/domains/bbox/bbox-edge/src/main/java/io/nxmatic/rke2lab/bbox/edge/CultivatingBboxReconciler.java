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
import java.util.Optional;
import org.osgi.service.component.annotations.Component;

/**
 * The CULTIVATING bbox edge — the single live door. Opens one session to the Bouygues Bbox router
 * via the embedded {@code java-bbox-api-client}, hands it to the library's {@link
 * ReservationReconciler} (which fetches the reservation table once), and APPLIES each desired
 * reservation. The client and its gson dependency are nested in this bundle, so no library type
 * ever reaches the host; the seam speaks only the home vocabulary.
 *
 * <p>One of the bbox reconciler PAIR: registered with {@code rke2lab.gardening=cultivating} so the
 * {@code @OsgiService} frontier picks it when the ambient RunGate is cultivating. Its twin, {@link
 * SurveyingBboxReconciler}, projects the plan without touching the router. Neither knows the mode —
 * the frontier chose; this one simply cultivates.
 */
@Component(service = BboxReconciler.class, property = "rke2lab.gardening=cultivating")
public final class CultivatingBboxReconciler implements BboxReconciler {

  @Override
  public List<BboxRowOutcome> reconcile(
      URI baseUri, Optional<String> adminPassword, List<BboxReservationRequest> requests) {
    final List<BboxRowOutcome> outcomes = new ArrayList<>(requests.size());
    final String password =
        adminPassword.orElseThrow(
            () ->
                new IllegalStateException(
                    "live bbox reconcile needs the router password — the host FACET amendment was"
                        + " empty; check .secrets:lan.bbox.password reached the seed"));
    try (BboxApiClient client = openClient(baseUri, password)) {
      final ReservationReconciler reconciler = new ReservationReconciler(client);
      for (BboxReservationRequest request : requests) {
        final RowOutcome outcome =
            reconciler.apply(
                new DesiredReservation(request.mac(), request.ip(), request.hostname()),
                Mode.APPLY);
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
