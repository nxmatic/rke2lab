package io.nxmatic.rke2lab.osgibench.startlevel.contributor;

import io.nxmatic.rke2lab.osgibench.startlevel.Grower;
import io.nxmatic.rke2lab.osgibench.startlevel.GrowerCensus;
import java.util.List;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * The level-5 contributor's census — the amend model's {@code AmendmentAssembler} analog. Its
 * {@code @Reference(MULTIPLE)} to {@link Grower} is bound by SCR at THIS component's activation, so
 * the count it captures is exactly the set of growers ACTIVE at the instant the contributor bound.
 * Constructor injection (like {@code DefaultSeedBroker}) freezes that snapshot. It publishes {@link
 * GrowerCensus} once ACTIVE; the proof reads the count and asserts it equals the growers pinned
 * below — whether the contributor was present at boot or installed at runtime with the cursor
 * already parked at its level.
 */
@Component(service = GrowerCensus.class)
public final class GrowerCensusComponent implements GrowerCensus {

  private final int observed;

  @Activate
  public GrowerCensusComponent(
      @Reference(cardinality = ReferenceCardinality.MULTIPLE) List<Grower> growers) {
    this.observed = growers.size();
  }

  @Override
  public int growersObserved() {
    return observed;
  }
}
