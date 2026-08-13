package io.seedmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.port.Crossing;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.List;
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

  @Test
  void aTrailOfMixedCrumbsRoundTripsThroughTheKindDiscriminator() {
    // The sealed Breadcrumb has no jackson annotation (the port is jackson-free); the @kind mixin
    // in
    // SeedCodec is what round-trips each crumb to its concrete shape. A trail mixing both shapes
    // must
    // survive encode→decode with each element rebuilt as the right type.
    final SeedCodec codec = new SeedCodec();
    final Trail trail =
        new Trail(
            List.of(
                new SourceCrumb("worktree", "worktree-facts", "abc123", true),
                new Crossing("systemd", "the systemd adapter is launched")));

    final Trail back = codec.decode(codec.encode(trail), Trail.class);

    assertInstanceOf(
        SourceCrumb.class, back.breadcrumbs().get(0), "a source crumb rebuilds as one");
    assertInstanceOf(Crossing.class, back.breadcrumbs().get(1), "a crossing rebuilds as one");
    assertEquals(trail, back, "the whole trail round-trips");
  }

  @Test
  void aLegacyTrailWithoutTheKindDiscriminatorDecodesAsASourceCrumb() {
    // BACKWARD COMPATIBILITY: a coquille persisted in the durable cellar BEFORE the sealed split
    // carries a flat crumb with NO @kind. defaultImpl = SourceCrumb makes it decode rather than
    // throw the InvalidTypeIdException that broke the drain on old Pulumi state.
    final SeedCodec codec = new SeedCodec();
    final String legacy =
        "{\"breadcrumbs\":[{\"domain\":\"worktree\",\"coordinate\":\"worktree-facts\","
            + "\"sha\":\"abc123\",\"dirty\":true}]}";

    final Trail back = codec.decode(legacy, Trail.class);

    assertEquals(
        new SourceCrumb("worktree", "worktree-facts", "abc123", true),
        back.breadcrumbs().get(0),
        "a legacy crumb with no @kind decodes as a SourceCrumb");
  }
}
