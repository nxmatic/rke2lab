package io.seedmatic.rke2lab.osgibench.startlevel;

/**
 * What a level-5 CONTRIBUTOR observed at its own activation — the count of {@link Grower} services
 * its {@code @Reference(MULTIPLE)} bound. The contributor publishes it once ACTIVE, so the proof
 * reads {@link #growersObserved()} and asserts it equals the number of growers pinned below: proof
 * that reaching the contributor level means the grower level is not merely STARTED but fully
 * SCR-ACTIVE (every grower service in the registry) before a contributor binds.
 */
public interface GrowerCensus {

  /** The number of {@link Grower} services bound at the contributor's activation. */
  int growersObserved();
}
