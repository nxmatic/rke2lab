package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.Tag;

/**
 * A within-run fact attached to a scenario's {@code ReportModel} — the tag the graft folds up into
 * the host tree, read back by the mechanism, never by hand-filtering a tag map. A CONTRIBUTABLE
 * interface, exactly like {@code SeedCoordinate}: this seam owns the CONCEPT (a tag has a {@code
 * type()} discriminator and mints a jGiven {@link Tag} carrying a value), each concern contributes
 * its own tags as an enum {@code implements ScenarioTag} — {@link GraftTag} (narration a scion
 * poses for the host: the live root) and {@link CellarTag} (the transactional cellar's: an
 * accumulated entry, the run's transaction id). So a new tag concern adds its own enum without
 * editing the center, and the mechanism ({@code ScenarioGraft}, the cellar drain) speaks only this
 * interface.
 */
public interface ScenarioTag {

  /** The tag {@code type} discriminator — how a reader finds this tag's kind in a tag map. */
  String type();

  /** A jGiven {@link Tag} of this kind carrying {@code value} — what is added to a model. */
  default Tag of(String value) {
    final Tag tag = new Tag(type(), value);
    tag.setType(type());
    return tag;
  }
}
