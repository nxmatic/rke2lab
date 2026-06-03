package io.nxmatic.rke2lab.netplan.api;

/** Service Provider Interface for canonical netplan synthesis. */
public interface NetplanSynthesisService {

  /** Stable provider identifier for diagnostics and policy checks. */
  String providerId();

  /** Derive canonical netplan addressing model for the supplied request. */
  NetplanSynthesisResult synthesize(NetplanSynthesisRequest request);
}
