/**
 * Cross-cutting domain annotations ({@link Transitional}, {@link GovernedBy} with its {@link
 * StagingGate} / {@link EnforcementLevel}) read by the staging extension. Build infrastructure, not
 * a business domain — set to {@link EnforcementLevel#IGNORE} for the spec-coverage gate it itself
 * powers (the markers are documented where they are USED, in the domain specs, not as a domain of
 * their own).
 */
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.IGNORE)
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.domain.annotations;
