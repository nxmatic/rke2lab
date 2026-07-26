package io.nxmatic.rke2lab.osgibench.startlevel.grower;

import io.nxmatic.rke2lab.osgibench.startlevel.Grower;
import org.osgi.service.component.annotations.Component;

/**
 * One of the level-4 growers; its {@code Grower} service must be in the registry before a level-5
 * contributor binds.
 */
@Component(service = Grower.class)
public final class BetaGrower implements Grower {

  @Override
  public String name() {
    return "beta";
  }
}
