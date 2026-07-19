package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.contract.HostTreePromoter;
import java.nio.file.Path;
import org.osgi.service.component.annotations.Component;

/**
 * The SURVEYING promoter — plans the reconcile without touching anything. It reads no live FS
 * (observes no drift), runs no jsync: it reports {@link Promotion#notPromoted()}, so the scion
 * narrates no PROMOTED tag and commits nothing, and every step renders PENDING via E9. A survey
 * cannot claim the live tree flipped, so it never does — the honest plan-only shape.
 *
 * <p>One of the HostTreePromoter PAIR: registered with {@code rke2lab.gardening=surveying} so the
 * frontier picks it when the ambient RunGate is surveying. Its twin, {@link
 * CultivatingHostTreePromoter}, does the real drift-observe + sync.
 */
@Component(service = HostTreePromoter.class, property = "rke2lab.gardening=surveying")
public final class SurveyingHostTreePromoter implements HostTreePromoter {

  @Override
  public Promotion promote(Path source, Path live, Path pivot, Path driftBase) {
    return Promotion.notPromoted();
  }
}
