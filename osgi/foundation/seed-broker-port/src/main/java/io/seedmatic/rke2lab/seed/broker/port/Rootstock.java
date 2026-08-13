package io.seedmatic.rke2lab.seed.broker.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a wire-record component as the ROOTSTOCK — the identity of the receiver the {@link Scion}s
 * graft onto (the join key, e.g. a checkpoint's {@code scenarioId}). Its twin: a scion is what is
 * grafted, a rootstock is what it is grafted onto. Storage-NEUTRAL like {@link Scion}: it names the
 * receiver's identity, never how a backend nests under it.
 *
 * <p>Reflected OSGi-side by the {@link SplitCoordinate} meta-handler alongside the scions: the
 * handler groups the scions under this rootstock's value and hands the frontier {@code rootstock →
 * [scions]}, so the frontier can nest each scion under its receiver (a Pulumi output under its
 * resource) WITHOUT holding the wire-record class or hardcoding a resource name — the rootstock
 * supplies the receiver, the scion supplies the field. On the read path the relation is not needed
 * (the domain re-associates by the rootstock value); on the write path it is (the nesting is
 * structural). See docs/architecture/osgi/seed-broker-spec.adoc.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Rootstock {}
