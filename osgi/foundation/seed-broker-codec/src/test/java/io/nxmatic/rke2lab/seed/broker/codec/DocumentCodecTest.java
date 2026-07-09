package io.nxmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentCodecTest {

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
}
