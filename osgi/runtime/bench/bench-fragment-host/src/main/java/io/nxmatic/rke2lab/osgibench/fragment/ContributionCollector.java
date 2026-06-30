package io.nxmatic.rke2lab.osgibench.fragment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * The host-side collector — the proof that a host {@code @Component} RECEIVES fragment-contributed
 * services into a dynamic collection, not merely that a fragment component activates. This is the
 * roster mechanism the doctor will use: {@code @Reference} a MULTIPLE/DYNAMIC {@code List} of the
 * contribution type, and whatever fragments contribute that service bind into it. The collector
 * lives in the HOST, owns no knowledge of any contributor, and never names an implementation — it
 * references only the {@link ContributedService} type. (Mirrors doctor-core referencing {@code
 * List<Specialist>}.)
 */
@Component(service = ContributionCollector.class, immediate = true)
public final class ContributionCollector {

  private final List<ContributedService> contributions = new CopyOnWriteArrayList<>();

  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  void bindContribution(ContributedService contribution) {
    contributions.add(contribution);
  }

  void unbindContribution(ContributedService contribution) {
    contributions.remove(contribution);
  }

  /** The contributions bound into this host so far — the roster. */
  public List<ContributedService> contributions() {
    return List.copyOf(contributions);
  }
}
