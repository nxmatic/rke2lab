package io.seedmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeedEnvelopeCodecTest {

  @Test
  void encodeDecodeRoundTripsAndValidationIsInertByDefault() {
    final SeedCodec codec = new SeedCodec();
    final String json = codec.encode(codec.decode("{\"action\":\"hold\"}"));
    assertTrue(json.contains("\"action\""));
    // validation OFF by default: a payload matching no schema still passes (the embedded posture —
    // the OSGi reader that parses the payload is the implicit validator).
    assertTrue(
        codec.validate("{\"unexpected\":1}", "readiness-verdict"),
        "validation is wired but OFF until the capstone turns it on");
  }

  /** A record with an Optional component — the shape an amendment-bearing input takes. */
  record OptionalHolder(Optional<String> present, Optional<String> absent) {}

  @Test
  void anEmptyOptionalOmitsItsKeyRatherThanEmittingNull() {
    // The no-null-on-the-wire invariant, LOCKED at the boundary: NON_ABSENT means an empty Optional
    // is absent, so its key is dropped — never serialized as "absent": null. Guards against a
    // mapper
    // config change silently reintroducing null values (the codebase's no-null rule).
    final SeedCodec codec = new SeedCodec();
    final String json = codec.encode(new OptionalHolder(Optional.of("x"), Optional.empty()));

    assertTrue(json.contains("\"present\""), "a present Optional keeps its key");
    assertFalse(json.contains("\"absent\""), "an empty Optional omits its key entirely");
    assertFalse(json.contains("null"), "no null value ever crosses the seam");

    final OptionalHolder back = codec.decode(json, OptionalHolder.class);
    assertEquals(Optional.of("x"), back.present());
    assertEquals(Optional.empty(), back.absent(), "an absent key round-trips to Optional.empty()");
  }
}
