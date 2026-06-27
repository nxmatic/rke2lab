package io.nxmatic.rke2lab.exchange.port;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The neutral envelope every host↔OSGi crossing carries: a document of a given type, owned by a
 * domain, whose body is a structured JSON tree. The host and OSGi share no data type — only this
 * record and the {@link JsonNode} payload cross. {@code coordinate} is the document type and the
 * schema key; {@code domain} names the owner. See docs/architecture/osgi/world-exchange-spec.adoc.
 */
public record Document(String domain, String coordinate, JsonNode payload) {}
