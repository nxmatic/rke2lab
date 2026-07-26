package io.nxmatic.rke2lab.osgibench.startlevel;

/**
 * A grower — the level-4 service analog of a {@code SeedHandler} in the amend model. Several
 * {@code @Component}s publish it from the grower bundle, pinned to the BUNDLES level; the proof
 * asserts every one is ACTIVE (its service registered) once the framework cursor reaches the
 * contributor level above. A marker: only its presence in the registry matters.
 */
public interface Grower {

  /** This grower's name — enough to tell the instances apart in a census. */
  String name();
}
