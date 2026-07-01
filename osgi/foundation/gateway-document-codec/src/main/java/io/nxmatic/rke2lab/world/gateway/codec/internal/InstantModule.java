package io.nxmatic.rke2lab.world.gateway.codec.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

/**
 * Maps {@link Instant} ↔ its ISO-8601 string ({@code Instant.toString()} / {@code Instant.parse}) —
 * the wire form every Document already uses for {@code when}/{@code recordedAt}. Hand-registered by
 * {@link DocumentCodec}, exactly like {@link WireEnumModule}: NO {@code jackson-datatype-jsr310}
 * dependency and NO {@code ServiceLoader<Module>} discovery (the SPI surface whose realm-isolation
 * regression cost an arc to close). Two lines of glue keep the codec free of jsr310.
 */
public final class InstantModule extends SimpleModule {

  public InstantModule() {
    addSerializer(Instant.class, new IsoSerializer());
    addDeserializer(Instant.class, new IsoDeserializer());
  }

  private static final class IsoSerializer extends JsonSerializer<Instant> {
    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(value.toString());
    }
  }

  private static final class IsoDeserializer extends JsonDeserializer<Instant> {
    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      return Instant.parse(p.getValueAsString());
    }
  }
}
