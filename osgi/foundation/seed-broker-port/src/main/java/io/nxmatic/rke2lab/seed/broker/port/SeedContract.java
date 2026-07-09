package io.nxmatic.rke2lab.seed.broker.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a wire-record to the coordinate whose {@link SeedEnvelope} payload it is the contract for,
 * by the coordinate's {@code slug()} (a {@code String}, not the enum reference: {@link
 * SeedCoordinate} is a contributable interface a domain implements, and an interface cannot be an
 * annotation element — nor may this foundation seam name a domain's coordinate). The record's
 * components ARE the wire shape.
 *
 * <p>Read at runtime by {@code SeedCodec.decode(SeedEnvelope, type)}: it checks the envelope's
 * coordinate equals the record's declared slug, so decoding an {@code intervention} envelope as a
 * {@code ReadinessVerdict} fails loudly rather than silently mis-parsing. An INTRA-domain guard
 * (the domain decodes its own documents), not a seam agreement — the host never holds the
 * wire-record class.
 *
 * <p>The binding lives on the record, not on the coordinate, so coordinates migrate to their
 * wire-record one at a time without any central type referencing every record at once. The
 * wire-record TYPE never crosses the boundary, only the serialized {@code String} payload, mapped
 * record↔String by each realm's own {@code SeedCodec}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SeedContract {

  /** The {@code slug()} of the coordinate this record is the wire contract for. */
  String value();
}
