package io.nxmatic.rke2lab.world.gateway.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a wire-record to the {@link Coordinate} whose {@link Document} payload it is the contract
 * for. The record's components ARE the coordinate's schema — a build-time projection of its
 * components is the JSON Schema (no hand-written schema, no {@code FIELD_*} string catalog). The
 * {@code SCHEMA_CONCORD} gate scans for this annotation: every {@code Coordinate} must have exactly
 * one wire-record carrying it, and that record must project to a meta-schema-valid schema.
 *
 * <p>The binding lives on the record, not on the {@code Coordinate} enum, so coordinates migrate to
 * their wire-record one at a time without the enum referencing every record at once.
 *
 * <p>Both realms hold the wire-record class (system-exported from this seam, one shared copy — like
 * {@link Document} itself); the TYPE never crosses the boundary, only the serialized {@code String}
 * payload, mapped record↔String by each realm's own {@code DocumentCodec}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DocumentContract {

  /** The coordinate this record is the wire contract for. */
  Coordinate value();
}
