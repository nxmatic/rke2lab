package io.nxmatic.rke2lab.bbox.port;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The flat outcome of reconciling one reservation — the home mirror of the library's {@code
 * RowOutcome}, carrying every field the host projects into its Pulumi resource outputs. Includes
 * the request identity ({@code cluster}/{@code node}) so the host correlates without re-matching,
 * the reconciled {@code action}, the bbox-side {@code mac}/{@code ip}/{@code hostname}, the
 * assigned {@code bboxId} (empty until the bbox knows one), the previous {@code ip}/{@code
 * hostname} (empty when unchanged or newly created), and a {@code failureMessage} on a FAILED row.
 * No library type crosses the seam.
 */
public record BboxRowOutcome(
    String cluster,
    String node,
    BboxAction action,
    String mac,
    String ip,
    String hostname,
    OptionalInt bboxId,
    Optional<String> previousIp,
    Optional<String> previousHostname,
    Optional<String> failureMessage) {}
