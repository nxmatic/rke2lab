package io.nxmatic.rk2lab.netplan.api;

import io.nxmatic.rk2lab.netplan.ClusterNetworkBlueprint;

/** Result contract for canonical netplan synthesis. */
public record NetplanSynthesisResult(ClusterNetworkBlueprint blueprint) {}
