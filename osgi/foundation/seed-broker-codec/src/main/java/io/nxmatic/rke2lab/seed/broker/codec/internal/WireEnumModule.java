package io.nxmatic.rke2lab.seed.broker.codec.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import io.nxmatic.rke2lab.seed.broker.port.WireEnum;
import java.io.IOException;
import java.util.Arrays;

/**
 * Maps any seam {@link WireEnum} ↔ its {@code slug()} generically, so a wire-record can hold a
 * closed-vocabulary enum typed (the typing the {@code FIELD_*} strings lacked) while the seam enum
 * itself carries no jackson annotation and stays flat. Serialization writes {@code slug()};
 * deserialization matches an enum constant by {@code slug()} (never the constant name). One module
 * for all coordinates' enums — registered once by {@link SeedCodec}.
 */
public final class WireEnumModule extends SimpleModule {

  public WireEnumModule() {
    setSerializerModifier(
        new BeanSerializerModifier() {
          @Override
          public JsonSerializer<?> modifySerializer(
              SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
            if (WireEnum.class.isAssignableFrom(beanDesc.getBeanClass())) {
              return new SlugSerializer();
            }
            return serializer;
          }
        });
    setDeserializerModifier(
        new BeanDeserializerModifier() {
          @Override
          public JsonDeserializer<?> modifyEnumDeserializer(
              DeserializationConfig config,
              JavaType type,
              BeanDescription beanDesc,
              JsonDeserializer<?> deserializer) {
            final Class<?> raw = type.getRawClass();
            if (WireEnum.class.isAssignableFrom(raw)) {
              return new SlugDeserializer(raw);
            }
            return deserializer;
          }
        });
  }

  private static final class SlugSerializer extends JsonSerializer<WireEnum> {
    @Override
    public void serialize(WireEnum value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(value.slug());
    }
  }

  private static final class SlugDeserializer extends JsonDeserializer<Object> {
    private final Class<?> enumClass;

    SlugDeserializer(Class<?> enumClass) {
      this.enumClass = enumClass;
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      final String slug = p.getValueAsString();
      return Arrays.stream(enumClass.getEnumConstants())
          .map(WireEnum.class::cast)
          .filter(e -> e.slug().equals(slug))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "unknown " + enumClass.getSimpleName() + " slug: " + slug));
    }
  }
}
