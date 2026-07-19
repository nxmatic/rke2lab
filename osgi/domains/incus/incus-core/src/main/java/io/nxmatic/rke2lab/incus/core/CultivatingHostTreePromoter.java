package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.contract.HostDriftEntry;
import io.nxmatic.rke2lab.incus.contract.HostTreePromoter;
import java.nio.file.Path;
import java.util.Optional;
import org.osgi.service.component.annotations.Component;

/**
 * The CULTIVATING promoter — the single live door of the reconcile. It performs the whole live
 * gesture the scion decided is due: OBSERVE the live tree's out-of-band drift against the pivot
 * (never a decision — reported so a rollback can read it), then SYNC the staging into {@code
 * host.live.d}. Composes the reusable core helpers so nothing is duplicated: {@link HostTreeDiffer}
 * (the drift delta), {@link HostTreeDeltaRenderer} (its report), {@link HostTreeSync} (the jsync
 * ACT, embedded in this bundle).
 *
 * <p>One of the HostTreePromoter PAIR: registered with {@code rke2lab.gardening=cultivating} so the
 * frontier picks it when the ambient RunGate is cultivating. Its twin, {@link
 * SurveyingHostTreePromoter}, touches nothing. It reports {@link Promotion#promoted()} = true so
 * the scion narrates the PROMOTED tag and commits the cellar entries — the mode never enters the
 * scion.
 */
@Component(service = HostTreePromoter.class, property = "rke2lab.gardening=cultivating")
public final class CultivatingHostTreePromoter implements HostTreePromoter {

  private final HostTreeDiffer differ = new HostTreeDiffer();
  private final HostTreeDeltaRenderer renderer = new HostTreeDeltaRenderer();
  private final HostTreeSync sync = new HostTreeSync();

  @Override
  public Promotion promote(Path source, Path live, Path pivot, Path driftBase) {
    // OBSERVE the live's deviation vs the pivot BEFORE the sync overwrites it, and record it as a
    // drift entry (its report rendered beside the staging). Never a decision — the staging is
    // authoritative, the drift is overwritten; the report is its only role.
    final HostTreeDelta driftDelta = differ.diff(pivot, live);
    final Optional<HostDriftEntry> drift =
        driftDelta.isEmpty()
            ? Optional.empty()
            : Optional.of(
                HostDriftEntry.of(
                    renderer.render(driftBase, driftDelta).toString(), pivot.toString()));
    // ACT — sync the staging into host.live.d (jsync, --delete, skip-flox).
    sync.sync(source, live);
    return new Promotion(true, drift);
  }
}
