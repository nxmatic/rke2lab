package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises the record→schema projection over real compiled bytecode: the fixture records below
 * carry the full vocabulary the six Document coordinates need (scalars, Instant, Optional, List of
 * a nested record, an opaque Map, and a slug-carrying enum).
 */
final class RecordSchemaProjectorTest {

  /** A slug-carrying seam-style enum: its wire value is the slug, not the constant name. */
  enum Colour {
    RED("red"),
    GREEN("green");

    private final String slug;

    Colour(String slug) {
      this.slug = slug;
    }

    public String slug() {
      return slug;
    }
  }

  record Leaf(String name, int count) {}

  record Root(
      String scenarioId,
      java.time.Instant when,
      boolean failed,
      Optional<String> override,
      List<Leaf> leaves,
      Colour colour,
      Map<String, Object> opaque) {}

  private byte[] bytesOf(Class<?> type) throws Exception {
    final String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      return in.readAllBytes();
    }
  }

  private RecordSchemaProjector projectorFor(Class<?>... types) {
    return new RecordSchemaProjector(
        internalName -> {
          for (Class<?> t : types) {
            if (t.getName().replace('.', '/').equals(internalName)) {
              try {
                return bytesOf(t);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          }
          return null;
        });
  }

  @Test
  void projectsTheFullVocabularyToAMetaSchemaValidSchema() throws Exception {
    final ObjectNode schema =
        projectorFor(Root.class, Leaf.class, Colour.class)
            .projectRoot(Root.class.getName().replace('.', '/'));

    // Self-check: the projected schema is itself valid against the 2020-12 meta-schema.
    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);

    assertEquals("object", schema.path("type").asText());
    final JsonNode props = schema.path("properties");

    assertEquals("string", props.path("scenarioId").path("type").asText());
    assertEquals("string", props.path("when").path("type").asText());
    assertEquals("date-time", props.path("when").path("format").asText());
    assertEquals("boolean", props.path("failed").path("type").asText());
    // Optional<String> → string, and NOT required.
    assertEquals("string", props.path("override").path("type").asText());
    // List<Leaf> → array of nested objects.
    assertEquals("array", props.path("leaves").path("type").asText());
    assertEquals("object", props.path("leaves").path("items").path("type").asText());
    assertEquals(
        "integer",
        props.path("leaves").path("items").path("properties").path("count").path("type").asText());
    // Enum → string constrained to slugs (not constant names).
    assertEquals("string", props.path("colour").path("type").asText());
    final JsonNode enumNode = props.path("colour").path("enum");
    assertTrue(enumNode.toString().contains("red"), "enum uses slug 'red', got " + enumNode);
    assertFalse(enumNode.toString().contains("RED"), "enum must not use the constant name RED");
    // Opaque Map → open object (no properties constraint).
    assertEquals("object", props.path("opaque").path("type").asText());

    // required carries the non-Optional components, not override.
    final String required = schema.path("required").toString();
    assertTrue(required.contains("scenarioId"), "scenarioId required");
    assertFalse(required.contains("override"), "Optional override must not be required");
  }
}
