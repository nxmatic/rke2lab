package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The records-as-contract SCHEMA_CONCORD invariant: a coordinate with no wire-record is a WARN
 * worklist entry; a coordinate whose wire-record projects to a meta-schema-valid schema is clean.
 * Reuses the compiled fixture records from {@link RecordSchemaProjectorTest}.
 */
final class SchemaConcordTest {

  private byte[] bytesOf(Class<?> type) throws Exception {
    final String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      return in.readAllBytes();
    }
  }

  @Test
  void aCoordinateWithoutAWireRecordIsAWorklistViolation() {
    final SchemaConcord concord =
        new SchemaConcord(
            Map.of("READINESS_VERDICT", "readiness-verdict"),
            Map.of(), // none migrated
            internalName -> null);
    final List<String> v = concord.violations();
    assertEquals(1, v.size());
    assertTrue(v.get(0).contains("no wire-record"), v.get(0));
  }

  @Test
  void aCoordinateWhoseWireRecordProjectsAValidSchemaIsClean() throws Exception {
    final String rootName = RecordSchemaProjectorTest.Root.class.getName().replace('.', '/');
    final String leafName = RecordSchemaProjectorTest.Leaf.class.getName().replace('.', '/');
    final String colourName = RecordSchemaProjectorTest.Colour.class.getName().replace('.', '/');
    final Map<String, byte[]> bytes =
        Map.of(
            rootName, bytesOf(RecordSchemaProjectorTest.Root.class),
            leafName, bytesOf(RecordSchemaProjectorTest.Leaf.class),
            colourName, bytesOf(RecordSchemaProjectorTest.Colour.class));

    final SchemaConcord concord =
        new SchemaConcord(
            Map.of("READINESS_VERDICT", "readiness-verdict"),
            Map.of("readiness-verdict", rootName),
            bytes::get);
    assertTrue(concord.violations().isEmpty(), "a valid projected schema is clean");
  }
}
