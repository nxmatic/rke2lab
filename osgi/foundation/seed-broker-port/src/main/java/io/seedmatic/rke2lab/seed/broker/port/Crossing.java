package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The SCENARIO-level {@link Breadcrumb} — a bare {@code (domain, coordinate)} crossing a failure
 * grew through, no git provenance. As a grafted failure returns up a (possibly nested) graft chain,
 * each crossing {@link Trail#prepend prepends} its own {@code Crossing} — so the failure's trail
 * carries the full crossing PATH from the root crossing down to the leaf where it grew.
 */
public record Crossing(String domain, String coordinate) implements Breadcrumb {}
