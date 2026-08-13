@org.osgi.annotation.versioning.Version("1.0.0")
// INSTANCE_DISCIPLINE is at the ERROR-locked default: the contract's two static helpers are gone
// (Cidr#parseAddress made package-private, ClusterNetworkBlueprint#topology folded into the
// constant).
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.netplan.contract;
