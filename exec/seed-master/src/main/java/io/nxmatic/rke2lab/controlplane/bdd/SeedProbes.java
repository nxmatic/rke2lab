package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * The collaborators the seed phases run through — the injection seam that decides live-vs-fake per
 * run. The driver builds the live set (each {@code Live*Probe} wrapping the real collaborator);
 * tests build a fake set. Bundled as one bag so the scenario carries a single inbound value rather
 * than one store key per probe. Each phase reads the probe it needs as
 * {@code @ExpectedScenarioState} after the scenario fans this out.
 */
public record SeedProbes(PreflightProbe preflight, BboxProbe bbox, IncusProbe incus) {

  /** The live set: each probe wraps its real collaborator. */
  public static SeedProbes live() {
    return new SeedProbes(new LivePreflightProbe(), new LiveBboxProbe(), new LiveIncusProbe());
  }
}
