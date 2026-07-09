package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The wire contract for the {@code readiness-verdict} {@link Document}: the provisioning {@link
 * Action} the OSGi authority decided, and a human-readable reason. Flat — the host reads only the
 * action to continue or stop; it never holds a doctor {@code Severity}. The record's components ARE
 * the schema (projected build-time by the {@code SCHEMA_CONCORD} gate); each realm maps it ↔ {@code
 * String} with its own jackson via {@code DocumentCodec}.
 */
@DocumentContract(Coordinate.READINESS_VERDICT)
public record ReadinessVerdict(Action action, String reason) {}
