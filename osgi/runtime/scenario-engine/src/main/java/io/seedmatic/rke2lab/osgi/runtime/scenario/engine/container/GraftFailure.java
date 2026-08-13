package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.seedmatic.rke2lab.seed.broker.port.Trail;

/**
 * The WIRE form of a grafted failure — a serializable POJO because no live {@link Throwable}
 * crosses the realm boundary, only the {@code ReportModel}'s JSON does (see {@link ScenarioGraft}).
 * The leaf scion captured its own failure STRUCTURED at the source (a {@link ThrownModel}, from the
 * live exception — never a printStackTrace re-parse); as the failure RETURNS up a (possibly nested)
 * graft chain, each {@code graftUnder} PREPENDS its own {@link
 * io.seedmatic.rke2lab.seed.broker.port.Crossing} to {@code path} — so the tag carries the full
 * crossing PATH from the root crossing down to the leaf where it grew. It REUSES the cellar's
 * {@link Trail} mechanism (§ fil-d-ariane): the cellar traces a VALUE back to its git source, this
 * traces a FAILURE back through its crossings. {@code Main} reloads each into a {@link
 * GraftThrowable} suppressed on the verdict.
 *
 * @param path the crossing {@link Trail}, root-first: each graft level prepended its {@code
 *     Crossing}
 * @param reason the leaf scion's STRUCTURED failure (type, message, frames, cause chain) — the
 *     mapper (de)serialises it, so the frames rebuild as real {@link StackTraceElement}s with no
 *     parsing
 */
public record GraftFailure(Trail path, ThrownModel reason) {}
