/**
 * The flat host control-plane. {@code REALM_BOUNDARY} is now build-enforced at its locked ERROR
 * default — the host holds no bundle-only doctor.records type; the world-exchange migration crosses
 * the boundary only as opaque Documents (see docs/architecture/osgi/world-exchange-spec.adoc).
 *
 * <p>One assembly-level law remains governed at {@code WARN} while its debt is paid down:
 *
 * <ul>
 *   <li>{@code DUPLICATE_REALM_CLASS} — cdk8s (org.cdk8s / software.constructs) is exported by the
 *       staged manifests carriers AND present flat in this assembly: a pre-existing two-realm
 *       duplication, listed until the carrier topology is corrected (its own increment).
 * </ul>
 *
 * Drop the pose to return to the locked ERROR default once that debt is cleared.
 */
@GovernedBy(value = StagingGate.DUPLICATE_REALM_CLASS, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
