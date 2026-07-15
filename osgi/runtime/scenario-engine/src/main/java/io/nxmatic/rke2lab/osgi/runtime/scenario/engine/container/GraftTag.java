package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.Tag;

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
public enum GraftTag {

  /**
   * The {@code host.live.d} tree the run's assets deploy into — the host renders the runbook here
   * (a live mutation), and the layout convention that yields it lives only in the incus scion, so
   * the scion poses it and the host reads it back rather than re-deriving the convention.
   */
  LIVE_ROOT("live-root");

  private final String type;

  GraftTag(String type) {
    this.type = type;
  }

  /** The tag {@code type} discriminator — how {@code ScenarioGraft} finds this tag in a tag map. */
  public String type() {
    return type;
  }

  /** A jGiven {@link Tag} of this kind carrying {@code value} — what a scion adds to its model. */
  public Tag of(String value) {
    final Tag tag = new Tag(type, value);
    tag.setType(type);
    return tag;
  }
}
