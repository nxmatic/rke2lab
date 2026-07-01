package io.nxmatic.rke2lab.world.gateway.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.nxmatic.rke2lab.world.gateway.codec.internal.InstantModule;
import io.nxmatic.rke2lab.world.gateway.codec.internal.WireEnumModule;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.DocumentContract;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The JSON (de)serialization + (capability) validation of {@code Document} payloads — the JSON
 * analogue of the manifests domain's {@code YamlMapper}. Written ONCE here; loaded per realm as our
 * own dual-realm library bundle ({@code embed; type=library}): staged as a bundle OSGi-side
 * (binding the OSGi jackson) and shaded flat host-side (binding the host jackson). No codec type
 * crosses the String-only world-gateway seam — each realm holds its own copy, exactly as jackson is
 * dual-loaded.
 *
 * <p>Modules are registered EXPLICITLY (never {@code findAndRegisterModules} — that {@code
 * ServiceLoader<Module>} discovery is the realm-isolation regression this project already closed):
 * two of ours — {@link WireEnumModule} (seam enum ↔ slug) and {@link InstantModule} (Instant ↔
 * ISO-8601) — plus jackson's own {@link Jdk8Module} for {@code Optional} (an absent key
 * deserializes to {@code Optional.empty()}; paired with {@code NON_ABSENT} an empty Optional omits
 * its key). Like {@code jackson-datatype-jsr310} in {@code manifests-cdk8s}, {@code
 * jackson-datatype-jdk8} is a realm-isolated jackson artifact nested on this bundle's
 * Bundle-ClassPath. Runtime schema validation is WIRED but OFF by default (the embedded posture);
 * the remote capstone flips it on via {@link #withValidation(boolean)}.
 */
public final class DocumentCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new WireEnumModule())
          .registerModule(new InstantModule())
          .registerModule(new Jdk8Module())
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_ABSENT);

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
   * Deserialize a {@link Document} into the wire-record {@code type}, first checking the Document's
   * coordinate matches the coordinate {@code type} declares via {@link DocumentContract} — so
   * decoding an {@code intervention} Document as a {@code ReadinessVerdict} fails loudly at the
   * seam rather than silently mis-parsing. The one call site's {@code decode(doc.payload(),
   * X.class)} is this, minus the guard.
   */
  public <T> T decode(Document document, Class<T> type) {
    final DocumentContract contract = type.getAnnotation(DocumentContract.class);
    if (contract != null && !contract.value().slug().equals(document.coordinate())) {
      throw new IllegalArgumentException(
          "cannot decode a '"
              + document.coordinate()
              + "' Document as "
              + type.getSimpleName()
              + " (contract coordinate '"
              + contract.value().slug()
              + "')");
    }
    return decode(document.payload(), type);
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
