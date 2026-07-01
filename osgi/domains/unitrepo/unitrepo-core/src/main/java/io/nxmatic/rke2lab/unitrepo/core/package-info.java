@org.osgi.annotation.versioning.Version("1.0.0")
// WARN level: drift is listed at each build (green), a visible backlog — drop the annotation to
// return to the ERROR default once the unitrepo domain is specified.
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.unitrepo.core;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
