@org.osgi.annotation.versioning.Version("1.0.0")
// WARN level: drift is listed at each build (green), a visible backlog — drop the annotation to
// return to the ERROR default once the netplan domain is specified.
@GovernedBy(value = Gate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.netplan;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.Gate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
