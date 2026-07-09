package io.nxmatic.rke2lab.seed.broker.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a wire-record component as an addressable GRAFT — a named sub-tree a domain wants filed
 * verbatim on the observability twin (the write frontier persists it, the read frontier collects it
 * back). Storage-NEUTRAL by design: it names the horticultural act (graft a scion onto a
 * rootstock), never the backend — so foundation carries no "Pulumi output" word, and a domain
 * declaring a graft never learns where it is stored. The graft's NAME is the component's own name,
 * so write and read reflect the same marker and cannot drift (this replaces the hand-rolled {@code
 * OUTPUT_KEY} constants a wire-record used to carry).
 *
 * <p>Reflected OSGi-side only, where the wire-record's class lives (its own realm) — the {@code
 * GraftCoordinate} meta-handler a domain contributes reads these components and hands the frontier
 * the grafts by name. The flat host holds no wire-record class, so it never reflects; it asks the
 * broker and affixes what it is handed. The runtime twin of the build-time schema projection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Graft {}
