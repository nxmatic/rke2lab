package io.nxmatic.rke2lab.maven.staging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The build-time SCHEMA_CONCORD guard under records-as-contract: every {@code Coordinate} must have
 * a wire-record ({@code @DocumentContract}), and that record must project to a schema valid against
 * the JSON-Schema meta-schema. Two violation kinds:
 *
 * <ul>
 *   <li>a coordinate with no wire-record yet — the WARN worklist entry that shrinks 6→0 as the
 *       coordinates migrate, gating the WARN→ERROR flip;
 *   <li>a wire-record whose projected schema is not meta-schema-valid — a real contract defect.
 * </ul>
 *
 * The schema is GENERATED from the record's components ({@link RecordSchemaProjector}) and
 * validated in-memory (networknt) — no schema file is written; the remote capstone re-projects the
 * same records in-realm when it turns runtime validation on. There is no field-string catalog to
 * reconcile: the record's components ARE the properties.
 */
final class SchemaConcord {

  private final Map<String, String> coordinateSlugs; // const → slug (all coordinates)
  private final Map<String, String> wireRecordBySlug; // slug → record internal name (migrated)
  private final Function<String, byte[]> bytesByInternalName;

  SchemaConcord(
      Map<String, String> coordinateSlugs,
      Map<String, String> wireRecordBySlug,
      Function<String, byte[]> bytesByInternalName) {
    this.coordinateSlugs = coordinateSlugs;
    this.wireRecordBySlug = wireRecordBySlug;
    this.bytesByInternalName = bytesByInternalName;
  }

  List<String> violations() {
    final List<String> lines = new ArrayList<>();
    final RecordSchemaProjector projector = new RecordSchemaProjector(bytesByInternalName);
    final JsonSchemaFactory factory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    for (String slug : coordinateSlugs.values()) {
      final String recordName = wireRecordBySlug.get(slug);
      if (recordName == null) {
        lines.add(slug + ": no wire-record (@DocumentContract) yet");
        continue;
      }
      try {
        final ObjectNode schema = projector.projectRoot(recordName);
        factory.getSchema(schema); // throws if the projected schema violates the meta-schema
      } catch (Exception ex) {
        String msg = ex.getMessage();
        if (msg == null && ex.getCause() != null) {
          msg = ex.getCause().getMessage();
        }
        if (msg == null) {
          msg = ex.getClass().getSimpleName();
        }
        lines.add(slug + ": generated schema invalid against meta-schema: " + msg);
      }
    }
    return lines;
  }
}
