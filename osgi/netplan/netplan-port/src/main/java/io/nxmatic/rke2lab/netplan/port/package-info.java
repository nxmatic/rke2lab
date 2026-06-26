@org.osgi.annotation.versioning.Version("1.0.0")
// WARN level: drift is listed at each build (green), a visible backlog — drop the annotation to
// return to the ERROR default once the netplan port is specified type-by-type. One marker covers
// the whole bundle's exported surface (.port AND .api).
// INSTANCE_DISCIPLINE at WARN: Cidr#parseAddress + ClusterNetworkBlueprint#topology are a visible
// backlog — drop this pose to return to the ERROR default once they are instances or @Exempt.
@GovernedBy(value = Gate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
@GovernedBy(value = Gate.INSTANCE_DISCIPLINE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.netplan.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.Gate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
