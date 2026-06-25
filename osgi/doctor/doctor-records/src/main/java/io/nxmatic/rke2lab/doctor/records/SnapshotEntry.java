package io.nxmatic.rke2lab.doctor.records;

import java.time.Instant;

/**
 * One point on the observation timeline: a monotonically increasing version and the instant it was
 * deployed. The pure replacement for the host {@code StackHistory.Entry} at the boundary — it
 * carries only what the diagnostic model reads (version, when), nothing of the backend's file
 * identity or deployment result. The host adapter keeps its own version→origin map to re-locate the
 * original when {@link SnapshotSource#at(SnapshotEntry)} is called.
 */
public record SnapshotEntry(int version, Instant when) {}
