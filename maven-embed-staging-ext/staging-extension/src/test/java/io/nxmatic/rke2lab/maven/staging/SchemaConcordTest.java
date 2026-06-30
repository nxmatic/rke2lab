package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SchemaConcordTest {

  @Test
  void aSchemaMissingAWrittenFieldIsAConcordViolation() throws Exception {
    // schema declares only "action"; code for readiness-verdict writes action + reason
    final Path dir = Files.createTempDirectory("schema-");
    Files.writeString(
        dir.resolve("readiness-verdict.schema.json"),
        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"}}}");
    final SchemaConcord concord =
        new SchemaConcord(dir, Map.of("readiness-verdict", Set.of("action", "reason")));
    final List<String> v = concord.violations();
    assertTrue(
        v.stream().anyMatch(s -> s.contains("reason")),
        "a field the code writes but the schema omits is a concord violation");
  }

  @Test
  void aValidSchemaWhoseFieldsMatchTheCodeIsClean() throws Exception {
    final Path dir = Files.createTempDirectory("schema-");
    Files.writeString(
        dir.resolve("readiness-verdict.schema.json"),
        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},"
            + "\"reason\":{\"type\":\"string\"}}}");
    final SchemaConcord concord =
        new SchemaConcord(dir, Map.of("readiness-verdict", Set.of("action", "reason")));
    assertTrue(concord.violations().isEmpty(), "matching fields + valid schema is clean");
  }

  @Test
  void aMalformedSchemaIsAMetaSchemaViolation() throws Exception {
    final Path dir = Files.createTempDirectory("schema-");
    Files.writeString(
        dir.resolve("readiness-verdict.schema.json"),
        "not-even-json"); // Totally invalid JSON — will fail parsing
    final SchemaConcord concord = new SchemaConcord(dir, Map.of("readiness-verdict", Set.of()));
    assertTrue(
        concord.violations().stream().anyMatch(s -> s.toLowerCase().contains("meta")),
        "a schema invalid against the meta-schema is a violation");
  }
}
