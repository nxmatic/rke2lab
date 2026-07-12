/**
 * The common gardening spine — the host-side runbook stages every seed exec rebuilds on, spoken in
 * the gardening register. This base package is NOT exported (see the bnd): it sits on the flat host
 * classpath, never wired in-container. See docs/architecture/osgi/seed-bdd-module-spec.adoc.
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.seed.bdd;
