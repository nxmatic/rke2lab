package io.nxmatic.rke2lab.world.gateway.port;

/**
 * The neutral envelope every host↔OSGi crossing carries: a document of a given type, owned by a
 * domain, whose body is a serialized JSON {@code String}. The host and OSGi share no data type —
 * only this record and its three {@code String} fields cross, all flat (JDK) types, so the seam
 * references nothing a bundle owns. Each world parses/serializes the {@code payload} with ITS OWN
 * jackson (the host's flat one; a bundle's own) — no jackson type ever crosses the boundary, which
 * is what keeps a {@code type=seam} pure (a {@code JsonNode} payload once leaked the jackson bundle
 * across the flat seam and caused a {@code LinkageError} in-container).
 *
 * <p>{@code coordinate} is the document type and the schema key; {@code domain} names the owner.
 * See docs/architecture/osgi/world-gateway-spec.adoc.
 */
public record Document(String domain, String coordinate, String payload) {}
