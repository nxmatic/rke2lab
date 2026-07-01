package io.nxmatic.rke2lab.world.gateway.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.world.gateway.codec.internal.WireEnumModule;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The JSON (de)serialization + (capability) validation of {@code Document} payloads — the JSON
 * analogue of the manifests domain's {@code YamlMapper}. Written ONCE here; loaded per realm: the
 * host shades this jar flat (binding the host's flat jackson), and {@code doctor-core} nests it on
 * its Bundle-ClassPath ({@code -includeresource;lib:=true}, binding the bundle's jackson). No codec
 * type crosses the String-only world-gateway seam — each realm holds its own copy, exactly as
 * jackson is dual-loaded. Runtime schema validation is WIRED but OFF by default (the embedded
 * posture); the remote capstone flips it on via {@link #withValidation(boolean)}.
 */
public final class DocumentCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new WireEnumModule());

  private final boolean validationEnabled;

  public DocumentCodec() {
    this(false);
  }

  private DocumentCodec(boolean validationEnabled) {
    this.validationEnabled = validationEnabled;
  }

  /** The off→on switch the capstone flips; embedded keeps the default (off). */
  public DocumentCodec withValidation(boolean enabled) {
    return new DocumentCodec(enabled);
  }

  public String encode(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to encode Document payload", ex);
    }
  }

  public JsonNode decode(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to decode Document payload", ex);
    }
  }

  /** Serialize a wire-record to its Document payload String (seam enums render as their slug). */
  public String encode(Object wireRecord) {
    try {
      return MAPPER.writeValueAsString(wireRecord);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to encode wire-record payload", ex);
    }
  }

  /** Deserialize a Document payload String into its wire-record (slugs resolve to seam enums). */
  public <T> T decode(String payload, Class<T> type) {
    try {
      return MAPPER.readValue(payload, type);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to decode payload into " + type.getSimpleName(), ex);
    }
  }

  /**
   * Validate a payload against the coordinate's schema. INERT while validation is off (embedded):
   * returns {@code true} without loading a schema. The capstone enables it; that path loads {@code
   * schema/<slug>.schema.json} from the classpath and runs networknt.
   */
  public boolean validate(String payload, String schemaSlug) {
    if (!validationEnabled) {
      return true;
    }
    throw new UnsupportedOperationException(
        "runtime validation is enabled only by the remote capstone (schema=" + schemaSlug + ")");
  }
}
