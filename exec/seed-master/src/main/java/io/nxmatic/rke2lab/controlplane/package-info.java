/**
 * The host control-plane (seed-master). {@link GovernedBy} at {@link EnforcementLevel#WARN} for
 * {@link StagingGate#REALM_BOUNDARY}: the flat-realm policy classes ({@code ControlplanePolicy},
 * {@code ManifestLinkPolicy}, {@code EntryGatePolicyEnforcer}) still reference {@code
 * manifests.contract} (a bundle-only package) — a pre-vision leak whose fix (moving the policy
 * vocabulary to {@code manifests.contract}, or off the flat realm) is a tracked debt, not this
 * work. WARN keeps the backlog visible and shrinking without breaking the build.
 */
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
