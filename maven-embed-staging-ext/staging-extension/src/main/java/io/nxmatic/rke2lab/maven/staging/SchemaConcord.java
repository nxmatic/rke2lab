package io.nxmatic.rke2lab.maven.staging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The build-time SCHEMA_CONCORD guard: per Document coordinate, (a) the schema is valid against the
 * JSON-Schema meta-schema, and (b) the schema's declared {@code properties} == the set of {@code
 * WorldGatewayCatalog.FIELD_*} values the coordinate's code references. The field set is discovered
 * by {@link CoordinateFieldUsage} (ASM over the bundle classes) and passed in, so this class stays
 * a pure schema↔fields comparison.
 */
final class SchemaConcord {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path schemaDir;
  private final Map<String, Set<String>> fieldsByCoordinateSlug;

  SchemaConcord(Path schemaDir, Map<String, Set<String>> fieldsByCoordinateSlug) {
    this.schemaDir = schemaDir;
    this.fieldsByCoordinateSlug = fieldsByCoordinateSlug;
  }

  List<String> violations() {
    final List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : fieldsByCoordinateSlug.entrySet()) {
      final String slug = e.getKey();
      final Path schemaFile = schemaDir.resolve(slug + ".schema.json");
      if (!Files.isRegularFile(schemaFile)) {
        continue; // no schema yet for this coordinate — the WARN worklist entry, not a hard error
      }
      try {
        final JsonNode schemaNode = read(schemaFile);
        lines.addAll(metaSchemaViolations(slug, schemaNode));
        lines.addAll(concordViolations(slug, schemaNode, e.getValue()));
      } catch (UncheckedIOException ex) {
        // Malformed JSON that can't even be parsed — a meta-schema violation
        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        lines.add(slug + ": meta-schema: " + msg);
      }
    }
    return lines;
  }

  private List<String> metaSchemaViolations(String slug, JsonNode schemaNode) {
    final List<String> lines = new ArrayList<>();
    try {
      final JsonSchemaFactory factory =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      // Attempt to create a schema from the node — if it's invalid against the meta-schema,
      // networknt will throw during construction
      factory.getSchema(schemaNode);
    } catch (Exception ex) {
      // Extract a meaningful message - networknt wraps various parsing/validation failures
      String msg = ex.getMessage();
      if (msg == null && ex.getCause() != null) {
        msg = ex.getCause().getMessage();
      }
      if (msg == null) {
        msg = ex.getClass().getSimpleName();
      }
      lines.add(slug + ": meta-schema: " + msg);
    }
    return lines;
  }

  private List<String> concordViolations(String slug, JsonNode schemaNode, Set<String> codeFields) {
    final List<String> lines = new ArrayList<>();
    final Set<String> schemaProps = new LinkedHashSet<>();
    final JsonNode props = schemaNode.path("properties");
    props.fieldNames().forEachRemaining(schemaProps::add);
    for (String field : codeFields) {
      if (!schemaProps.contains(field)) {
        lines.add(slug + ": field written/read by code but absent from schema: " + field);
      }
    }
    for (String prop : schemaProps) {
      if (!codeFields.contains(prop)) {
        lines.add(slug + ": schema declares property never used by code: " + prop);
      }
    }
    return lines;
  }

  private static JsonNode read(Path file) {
    try {
      return MAPPER.readTree(Files.readString(file));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read schema " + file, ex);
    }
  }
}
