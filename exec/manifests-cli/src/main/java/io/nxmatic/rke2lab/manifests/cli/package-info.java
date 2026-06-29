/**
 * The manifests CLI exec. Governed at {@code DUPLICATE_REALM_CLASS = WARN}: cdk8s (org.cdk8s /
 * software.constructs) is exported by the staged manifests carriers AND present flat in this
 * assembly — a pre-existing two-realm duplication, listed as a shrinking backlog until the carrier
 * topology is corrected (its own increment). Drop this pose to return to the locked ERROR default
 * once the debt is cleared.
 */
@GovernedBy(value = StagingGate.DUPLICATE_REALM_CLASS, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.manifests.cli;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
