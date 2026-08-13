package io.seedmatic.rke2lab.osgi.bnd;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One clause of an OSGi manifest header: a name (a package name, a symbolic name, a capability
 * namespace) plus its attributes and directives. The smallest value the bnd-written header surface
 * decomposes into — an immutable record we query, never a string we re-scan. {@link OsgiHeader}
 * splits a header into these; {@link EmbedCapability} is a typed view of one.
 */
public record Clause(String name, Map<String, String> attributes) {

  public Clause {
    attributes = Map.copyOf(attributes);
  }

  /**
   * Parse a single clause string ({@code
   * org.osgi.service.component;version="[1.5,2)";resolution:=mandatory}) into its name and
   * attributes. A directive ({@code key:=value}) keeps its bare key; quotes are stripped from
   * values — only plain attributes carry selection/version data we read.
   */
  public static Clause parse(String clause) {
    final String[] parts = clause.split(";");
    final String name = parts[0].trim();
    final Map<String, String> attributes = new LinkedHashMap<>();
    for (int i = 1; i < parts.length; i++) {
      final String p = parts[i].trim();
      final int eq = p.indexOf('=');
      if (eq > 0) {
        final String key = p.substring(0, eq).replace(":", "").trim();
        final String value = p.substring(eq + 1).replace("\"", "").trim();
        attributes.put(key, value);
      }
    }
    return new Clause(name, attributes);
  }

  /**
   * The {@code version} attribute's lower bound, or {@code null} if the clause states no version.
   */
  public String versionLowerBound() {
    final String raw = attributes.get("version");
    if (raw == null) {
      return null;
    }
    return raw.startsWith("[") || raw.startsWith("(") ? raw.substring(1).split(",")[0].trim() : raw;
  }

  /**
   * This clause as a system-bundle export clause: the name kept, an explicit version narrowed to
   * its lower bound (so an importer's range is satisfied), every other directive/attribute dropped.
   * The import→export mirroring, expressed on the clause that carries it.
   */
  public String asExportClause() {
    final String lower = versionLowerBound();
    return lower == null ? name : name + ";version=" + lower;
  }
}
