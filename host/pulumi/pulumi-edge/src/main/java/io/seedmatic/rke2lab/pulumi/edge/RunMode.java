package io.seedmatic.rke2lab.pulumi.edge;

import com.pulumi.deployment.Deployment;

/**
 * The state a seed run executes in — the single source the whole run's live/materialise behavior
 * projects from. Three values, one impossible fourth removed: a run is either outside Pulumi
 * ({@link #STANDALONE}) or under it, and under it either a dry-run ({@link #PULUMI_PREVIEW}) or a
 * real apply ({@link #PULUMI_RUN}); "standalone preview" cannot occur.
 *
 * <p>This is Pulumi vocabulary and lives at the Pulumi edge on purpose: the domain must not see it.
 * A phase reads the two PROJECTIONS below, never this enum — {@link #playsLive()} through {@link
 * LiveGate}, {@link #materialises()} through the resource path. The two were, before this, two
 * correlated booleans ({@code pulumiMode} + an inline {@code isDryRun}) scattered across the
 * pipeline; naming the state they project from removes the impossible combination and the scatter.
 */
public enum RunMode {

  /** Outside Pulumi: touches the real system to OBSERVE it (ssh, kubectl), materialises nothing. */
  STANDALONE,

  /**
   * Under Pulumi, dry-run: touches nothing (bodies render deferred), materialises under dry-run.
   */
  PULUMI_PREVIEW,

  /** Under Pulumi, real apply: touches the real system AND materialises resources. */
  PULUMI_RUN;

  /**
   * The mode this run is in. Standalone when {@code pulumiMode} is false; otherwise the ambient
   * Pulumi deployment's dry-run flag tells preview from apply. The {@code &&} short-circuit means
   * {@link Deployment#getInstance()} is read ONLY under Pulumi, never standalone (where no
   * deployment exists) — this is the ONE place the dry-run flag is read.
   */
  public static RunMode detect(boolean pulumiMode) {
    if (!pulumiMode) {
      return STANDALONE;
    }
    return Deployment.getInstance().isDryRun() ? PULUMI_PREVIEW : PULUMI_RUN;
  }

  /**
   * Whether an action that touches the real system runs live rather than deferred. Open for a
   * standalone run (it observes the real system) and a Pulumi apply; closed only during a Pulumi
   * preview, where touching the live system would hang or lie. Projected through {@link LiveGate}.
   */
  public boolean playsLive() {
    return this != PULUMI_PREVIEW;
  }

  /**
   * Whether a mutation materialises Pulumi resources. True under Pulumi (preview registers them
   * dry-run, apply for real); false standalone, which takes the resource-free path. Projected
   * through the resource pipeline (the former {@code pulumiMode} flag).
   */
  public boolean materialises() {
    return this != STANDALONE;
  }
}
