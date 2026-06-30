package io.nxmatic.rke2lab.osgibench.fragment.contribution;

import io.nxmatic.rke2lab.osgibench.fragment.ContributedService;
import org.osgi.service.component.annotations.Component;

/**
 * The proof's subject: a DS {@code @Component} that lives in a FRAGMENT, not in a started bundle.
 * The fragment has no lifecycle — it attaches to its host as extra resources (classes + {@code
 * OSGI-INF/*.xml} + the {@code Service-Component} header bnd generates here). When the host is
 * resolved and started, SCR must scan the host's fragments too (DS 112.4.1) and activate this
 * component in the host's context, publishing {@link ContributedService}. If {@code
 * awaitService(ContributedService.class)} returns it, the fragment-contribution model holds.
 */
@Component(service = ContributedService.class, immediate = true)
public final class FragmentContributedComponent implements ContributedService {

  @Override
  public String contribution() {
    return "activated-from-fragment";
  }
}
