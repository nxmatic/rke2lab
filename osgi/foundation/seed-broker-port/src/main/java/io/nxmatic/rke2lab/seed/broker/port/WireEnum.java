package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The seam's closed-vocabulary enums ({@link Action}, {@link SymptomKind}, …) whose wire form is a
 * kebab-case {@code slug()}, never the constant name. A wire-record may hold such an enum typed
 * (e.g. {@code ReadinessVerdict(Action, …)}) rather than a loose {@code String} — the typing the
 * {@code FIELD_*} strings lacked — because each realm's {@code DocumentCodec} maps {@code WireEnum
 * ↔ slug} generically (one jackson module for all of them), so the seam itself carries no jackson
 * annotation and stays flat.
 */
public interface WireEnum {

  /** The kebab-case wire value placed in a Document payload; the codec (de)serializes on it. */
  String slug();
}
