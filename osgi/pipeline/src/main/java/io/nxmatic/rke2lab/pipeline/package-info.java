@org.osgi.annotation.versioning.Version("1.0.0")
// INSTANCE_DISCIPLINE at WARN — backlog: FluentTopicRunner#runDuring. Drop once it is an instance.
@GovernedBy(value = Gate.INSTANCE_DISCIPLINE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.pipeline;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.Gate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
