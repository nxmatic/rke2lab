package io.nxmatic.rke2lab.seed.broker.port;

import java.util.Map;

/**
 * The stack config's readiness deadlines, published ONCE as an ambient service the whole run shares
 * — the twin of {@link RunGate}: a fact of the WHOLE run, resolved from the registry by each
 * readiness scion, not carried on an envelope. The host projects it from {@code rke2lab:readiness:}
 * and publishes it into the framework at boot; the {@code ReadinessBudgetExtension} resolves it and
 * folds it over a scenario's {@code @ReadinessDeadlines} defaults.
 *
 * <p>Two levels: a {@link #global} default that applies to every checkpoint, and a {@link
 * #perCheckpoint} map (keyed by the checkpoint slug — {@code systemd-adapter}, {@code
 * cluster-readiness}) that overrides the global for one checkpoint. {@link #forCheckpoint} resolves
 * the effective override for a checkpoint by folding its per-checkpoint entry over the global
 * (per-checkpoint field wins, else the global field), leaving the annotation default to fill any
 * half still absent. Lives in the seam both realms share (a single system-exported copy), so no
 * {@code com.pulumi} type and no dual-realm bundle type crosses.
 */
public record ReadinessOverrides(
    ReadinessDeadlineOverride global, Map<String, ReadinessDeadlineOverride> perCheckpoint) {

  /** No overrides at all — every deadline comes from the annotation. */
  public static final ReadinessOverrides NONE =
      new ReadinessOverrides(ReadinessDeadlineOverride.NONE, Map.of());

  public ReadinessOverrides {
    perCheckpoint = Map.copyOf(perCheckpoint);
  }

  /**
   * The effective override for {@code checkpoint}: its per-checkpoint entry folded over the global
   * (each half taken from the per-checkpoint value if present, else the global). What the entry and
   * the global both leave empty stays empty — the annotation default fills it downstream.
   */
  public ReadinessDeadlineOverride forCheckpoint(String checkpoint) {
    final ReadinessDeadlineOverride specific =
        perCheckpoint.getOrDefault(checkpoint, ReadinessDeadlineOverride.NONE);
    return new ReadinessDeadlineOverride(
        specific.connect().or(global::connect), specific.ready().or(global::ready));
  }
}
