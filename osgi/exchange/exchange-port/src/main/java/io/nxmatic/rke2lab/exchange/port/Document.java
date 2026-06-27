package io.nxmatic.rke2lab.exchange.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The neutral envelope every host↔OSGi crossing carries: a document of a given type, owned by a
 * domain, whose body is a structured JSON tree. The host and OSGi share no data type — only this
 * record and the {@link JsonNode} payload cross. {@code coordinate} is the document type and the
 * schema key; {@code domain} names the owner. See docs/architecture/osgi/world-exchange-spec.adoc.
 */
public record Document(String domain, String coordinate, JsonNode payload) {

  /**
   * A fresh, empty payload to fill and hand to {@link #Document(String, String, JsonNode)}. The
   * seam owns the envelope, so it owns the payload's construction too — every producer (the
   * authority, the doctor, the host stages) builds its body here rather than each holding its own
   * {@code ObjectMapper}, so the JSON construction lives in one place. No mapper is needed: a
   * payload is only assembled, never parsed, at the crossing.
   */
  public static ObjectNode newPayload() {
    return JsonNodeFactory.instance.objectNode();
  }
}
