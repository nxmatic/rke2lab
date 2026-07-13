package io.nxmatic.rke2lab.seed.broker.shape;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

/**
 * The amont introspection engine: project a wire-record's JSON Schema (Draft 2020-12) from its
 * {@code RecordComponents}. The symmetric twin of {@code DoctorSplitReflector} — Split walks a
 * reaped record's {@code @Scion} components AVAL, this projects an input record's schema AMONT. A
 * domain's {@code ShapeReflector} holds the {@code @SeedContract}-indexed wire-record class and
 * drives this projector, reaping an opaque JSON-Schema {@code String} across the door; the host
 * learns the payload's shape without ever holding the class (see
 * docs/architecture/osgi/seed-broker-spec.adoc § shape).
 *
 * <p>The generation is victools' — we do NOT hand-roll a schema generator. The jackson module reads
 * jackson's own annotations (so a wire-record's {@code @JsonProperty}, enum-slug rendering, and
 * {@code Optional} → non-{@code required} project exactly as the codec serializes them), and {@code
 * RESPECT_JSONPROPERTY_REQUIRED} makes a component's requiredness explicit rather than guessed.
 * Nested wire-records are inlined by the preset. One immutable {@link SchemaGenerator} is built
 * once and reused (victools generators are thread-safe).
 */
public final class RecordSchemaProjector {

  private final SchemaGenerator generator;

  public RecordSchemaProjector() {
    final JacksonModule jackson = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    final SchemaGeneratorConfig config =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(jackson)
            .build();
    this.generator = new SchemaGenerator(config);
  }

  /**
   * Project {@code wireRecord}'s JSON Schema. The returned node is the Draft 2020-12 schema of the
   * record's components — the payload a sower must supply for the coordinate this record is the
   * {@code @SeedContract} for. Serialized to a {@code String} by the caller's own codec (the reaped
   * envelope carries it opaque).
   */
  public JsonNode project(Class<?> wireRecord) {
    return generator.generateSchema(wireRecord);
  }
}
