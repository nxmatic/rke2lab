package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

/**
 * The vocabulary of within-run facts a scion attaches to its {@code ReportModel} for the host to
 * read back after the graft — the EPHEMERAL cellar (§ seed-broker-spec, two cellars: durable vs
 * ephemeral). A scion POSES a tag on its own model ({@code
 * model.addTag(GraftTag.LIVE_ROOT.of(v))}); the graft merges the scion's tag map into the host
 * tree, and the host READS it through the graft mechanism ({@code ScenarioGraft.graftedValue}),
 * never by hand-filtering a tag map.
 *
 * <p>It lives in {@code .container} (the bundle-visible half of scenario-engine, the world both
 * realms share) because BOTH ends name it: the scion (a bundle, in-container) poses, and {@code
 * ScenarioGraft} (host-flat, base package) reads — one module, one constant, no duplicated string.
 * The tag stays NARRATION: it rides the observability channel and renders in the runbook; it is not
 * a decision input (those are the verdict + the amendment).
 */
public enum GraftTag implements ScenarioTag {

  /**
   * The {@code host.live.d} tree the run's assets deploy into — the host renders the runbook here
   * (a live mutation), and the layout convention that yields it lives only in the incus scion, so
   * the scion poses it and the host reads it back rather than re-deriving the convention.
   */
  LIVE_ROOT("live-root"),

  /**
   * A scion's failure, posed on the host tree at graft RECEPTION ({@code ScenarioGraft.graftUnder})
   * as a SeedCodec-encoded {@link GraftFailure} — the structured channel (distinct from the folded
   * {@code errorMessage}/{@code stackTrace} text, which is for the runbook's human render) that
   * {@code Main} reloads into a {@link GraftThrowable} suppressed on the verdict, one per crossing.
   */
  GRAFT_FAILURE("graft-failure"),

  /**
   * A scenario's OWN failure, captured STRUCTURED at the source ({@code ScenarioPlayer}, where the
   * live {@link Throwable} still exists) as a SeedCodec-encoded {@link ThrownModel} — the callee's
   * self-report. {@code graftUnder} reads it off a failed scion, wraps it in a {@link GraftFailure}
   * with the crossing path (the caller's enrichment), and re-poses that as a {@link
   * #GRAFT_FAILURE}. Structured from the live exception, so the frames NEVER pass through a
   * printStackTrace re-parse.
   */
  SCENARIO_FAILURE("scenario-failure");

  private final String type;

  GraftTag(String type) {
    this.type = type;
  }

  @Override
  public String type() {
    return type;
  }
}
