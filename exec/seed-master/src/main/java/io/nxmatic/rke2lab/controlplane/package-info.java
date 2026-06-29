/**
 * The flat host control-plane. Governed at {@code WARN} for two assembly-level laws while debt is
 * paid down (see docs/architecture/osgi/world-exchange-spec.adoc):
 *
 * <ul>
 *   <li>{@code REALM_BOUNDARY} — every flat-realm class still referencing a bundle-only
 *       doctor.records type, a shrinking worklist as the host↔OSGi surface migrates to Documents.
 *   <li>{@code DUPLICATE_REALM_CLASS} — cdk8s (org.cdk8s / software.constructs) is exported by the
 *       staged manifests carriers AND present flat in this assembly: a pre-existing two-realm
 *       duplication, listed until the carrier topology is corrected (its own increment).
 * </ul>
 *
 * Drop each pose to return to the locked ERROR default once that debt is cleared.
 */
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
@GovernedBy(value = StagingGate.DUPLICATE_REALM_CLASS, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
