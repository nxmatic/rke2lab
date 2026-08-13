package io.seedmatic.rke2lab.netplan.contract;

/** Result contract for canonical netplan synthesis. */
public record NetplanSynthesisResult(ClusterNetworkBlueprint blueprint) {}
