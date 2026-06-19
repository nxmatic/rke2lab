package io.nxmatic.rke2lab.netplan.api;

import io.nxmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;

/** Result contract for canonical netplan synthesis. */
public record NetplanSynthesisResult(ClusterNetworkBlueprint blueprint) {}
