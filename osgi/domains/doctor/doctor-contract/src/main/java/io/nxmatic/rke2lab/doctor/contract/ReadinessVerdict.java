package io.nxmatic.rke2lab.doctor.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the {@code readiness-verdict} {@code SeedEnvelope}: the provisioning {@link
 * Action} the OSGi authority decided, and a human-readable reason. Flat — the host reads only the
 * action to continue or stop; it never holds a doctor {@code Severity}. The record's components ARE
 * the wire shape; each realm maps it ↔ {@code String} with its own jackson via {@code SeedCodec}.
 */
@SeedContract("readiness-verdict")
public record ReadinessVerdict(Action action, String reason) {}
