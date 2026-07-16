package io.nxmatic.rke2lab.manifests.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.ShapeCoordinate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shape verb manifests contributes: given a seed naming the {@code runbook} coordinate, the
 * reflector projects the JSON Schema of {@link ManifestsRunbookInput} OSGi-side and hands it back
 * as an opaque String, so a sower learns the payload shape holding no manifests class. Pins the
 * behaviors the sower relies on: the reflector serves the manifests shape meta-coordinate; the
 * projected schema is a non-empty object naming the yaml concern keys ({@code link}, {@code
 * debug}); an unknown coordinate is refused. Plus the invariant the whole config path rests on — a
 * yaml-shaped map (string "true", nested {enabled} wrappers) decodes into the wire-record verbatim.
 */
class ManifestsShapeReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private static final ManifestsShapeReflector REFLECTOR = new ManifestsShapeReflector();

  @Test
  void serves_the_manifests_shape_meta_coordinate() {
    assertEquals(new ShapeCoordinate("manifests"), REFLECTOR.serves());
  }

  @Test
  void projects_a_schema_naming_the_yaml_concern_keys() {
    // A sower asks the shape of the runbook coordinate — the seed carries that coordinate's slug.
    final SeedEnvelope ask = SeedEnvelope.of(new RunbookCoordinate("manifests"), "{}");

    final SeedEnvelope reaped = REFLECTOR.handle(ask, Optional.empty());

    assertEquals(ShapeCoordinate.SLUG, reaped.coordinate());
    final var schema = CODEC.decode(reaped.payload());
    assertEquals("object", schema.path("type").asText());
    // The top-level properties ARE the yaml concern keys — the design constraint the generic pluck
    // depends on (the sower plucks policy.<name> for each named property).
    final var properties = schema.path("properties");
    assertTrue(properties.has("link"), "schema must name the 'link' concern");
    assertTrue(properties.has("debug"), "schema must name the 'debug' concern");
  }

  @Test
  void refuses_a_coordinate_it_describes_no_shape_for() {
    final SeedEnvelope ask = SeedEnvelope.of(new ShapeCoordinate("manifests"), "{}");
    assertThrows(IllegalArgumentException.class, () -> REFLECTOR.handle(ask, Optional.empty()));
  }

  @Test
  void decodes_a_yaml_shaped_map_into_the_wire_record_verbatim() {
    // The exact subtree a sower plucks from Pulumi.dev.yaml: strings for booleans, {enabled}
    // nesting
    // for debug. The codec (jackson) coerces it into the typed wire-record — the verbatim-pluck
    // invariant. The host copies this subtree blindly; all coercion is OSGi-side.
    final Map<String, Object> link =
        Map.of(
            "gitops", "true",
            "networking", "true",
            "clusterApi", "true",
            "storage", "true",
            "mesh", "false",
            "highAvailability", "true",
            "cicd", "true");
    final Map<String, Object> debug =
        Map.of(
            "mesh", Map.of("enabled", "true"),
            "networking", Map.of("enabled", "false"),
            "nriPlugins", Map.of("flox", Map.of("enabled", "true")));

    final ManifestsRunbookInput input =
        CODEC.fromMap(Map.of("link", link, "debug", debug), ManifestsRunbookInput.class);

    assertTrue(input.link().clusterApi());
    assertFalse(input.link().mesh());
    assertTrue(input.debug().mesh().enabled());
    assertFalse(input.debug().networking().enabled());
    assertTrue(input.debug().nriPlugins().flox().enabled());
  }
}
