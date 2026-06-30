package io.nxmatic.rke2lab.world.gateway.port;

/**
 * The gateway verb the host calls to turn a readiness-checkpoint outcome into a provisioning
 * verdict. The host hands a {@code readiness-checkpoint} {@link Document} (the scenario id, whether
 * it failed, the operator's raw override) and receives a {@code readiness-verdict} {@link Document}
 * whose {@code action} field is {@code stop} or {@code continue-degraded}. The authority — not the
 * host — owns the severity vocabulary and the decision. See
 * docs/architecture/osgi/world-gateway-2a-document-foundation-spec.adoc.
 */
public interface ReadinessAuthority {

  /** Assess a checkpoint Document and return the provisioning verdict as a Document. */
  Document assess(Document checkpoint);
}
