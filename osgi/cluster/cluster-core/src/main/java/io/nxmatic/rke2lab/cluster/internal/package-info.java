/**
 * The cluster domain's internal model. Reported at {@code WARN} by the build-time spec-coverage
 * guard: its exported types are not yet documented in {@code docs/}, so drift is listed at each
 * build (a visible, shrinking backlog) without failing it. Drop the annotation to return to the
 * {@code ERROR} default once a cluster design spec exists.
 */
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.cluster.internal;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
