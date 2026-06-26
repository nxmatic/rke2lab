@org.osgi.annotation.versioning.Version("1.0.0")
// SPEC_COVERAGE at WARN: drift listed at each build (green) — drop once the netplan port is
// specified type-by-type. One marker covers the whole bundle's exported surface (.port AND .api).
// INSTANCE_DISCIPLINE is back at the ERROR-locked default: the port's two static helpers are gone
// (Cidr#parseAddress made package-private, ClusterNetworkBlueprint#topology folded into the
// constant).
@GovernedBy(value = Gate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.netplan.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.Gate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
