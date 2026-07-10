package io.nxmatic.rke2lab.seed.broker.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a wire-record component as a SCION — a named sub-tree grafted verbatim onto the stored tree
 * (the write frontier persists it, the read frontier collects it back). An annotation names a ROLE,
 * not a verb: the component IS the scion (what is grafted), so this is {@code @Scion}, not the
 * former {@code @Graft} (the act). Storage-NEUTRAL by design: it names the horticultural role,
 * never the backend — so foundation carries no "Pulumi output" word, and a domain declaring a scion
 * never learns where it is stored. The scion's NAME is the component's own name, so write and read
 * reflect the same marker and cannot drift (this replaces the hand-rolled {@code OUTPUT_KEY}
 * constants a wire-record used to carry).
 *
 * <p>Its twin is {@link Rootstock}, marking the receiver identity the scions graft onto. Reflected
 * OSGi-side only, where the wire-record's class lives (its own realm) — the {@link SplitCoordinate}
 * meta-handler a domain contributes reads these components and hands the frontier the scions
 * grouped under their rootstock. The flat host holds no wire-record class, so it never reflects; it
 * asks the broker and affixes what it is handed. The runtime twin of the build-time schema
 * projection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Scion {}
