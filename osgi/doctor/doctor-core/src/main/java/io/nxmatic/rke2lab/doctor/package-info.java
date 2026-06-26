@org.osgi.annotation.versioning.Version("1.0.0")
// INSTANCE_DISCIPLINE at WARN: ExactRosterDoctor#over is a static helper, listed as a visible
// backlog — drop this annotation to return to the ERROR default once it is an instance.
@GovernedBy(value = Gate.INSTANCE_DISCIPLINE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.Gate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
