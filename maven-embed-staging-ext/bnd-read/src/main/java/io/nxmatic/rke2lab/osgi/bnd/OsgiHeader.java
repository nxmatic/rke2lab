package io.nxmatic.rke2lab.osgi.bnd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A parsed OSGi manifest header — the list of {@link Clause}s an {@code Export-Package} / {@code
 * Import-Package} / {@code Provide-Capability} value decomposes into. The one piece neither the JDK
 * nor {@code osgi.core} provides: splitting on the commas that are NOT inside a quoted version
 * range. Read the bytes with the JDK Manifest API, then {@link #parse} the header value into this
 * to query it as an instance. The build-time extension and the runtime boot model share this, so a
 * header is parsed identically on both sides.
 */
public record OsgiHeader(List<Clause> clauses) {

  public OsgiHeader {
    clauses = List.copyOf(clauses);
  }

  /** Parse a header value into its clauses; an absent or blank header is an empty header. */
  public static OsgiHeader parse(String header) {
    if (header == null || header.isBlank()) {
      return new OsgiHeader(List.of());
    }
    final List<Clause> clauses = new ArrayList<>();
    for (String clause : splitClauses(header)) {
      clauses.add(Clause.parse(clause));
    }
    return new OsgiHeader(clauses);
  }

  /** The bare package/clause names, in header order. */
  public Set<String> names() {
    final Set<String> names = new LinkedHashSet<>();
    for (Clause clause : clauses) {
      names.add(clause.name());
    }
    return names;
  }

  /**
   * This header (read as an {@code Import-Package}) mirrored into system-bundle export clauses —
   * each import's package kept, its range narrowed to the lower bound, so it resolves against a
   * flat classpath. The bundle's own exports are not mirrored; only what it imports needs wiring.
   */
  public Set<String> asSystemExports() {
    final Set<String> exports = new LinkedHashSet<>();
    for (Clause clause : clauses) {
      exports.add(clause.asExportClause());
    }
    return exports;
  }

  /**
   * The clause whose name equals {@code name}, or {@code null} — e.g. the embed capability clause.
   */
  public Clause named(String name) {
    return clauses.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
  }

  /** Split an OSGi header value on commas that are NOT inside a quoted version range. */
  static List<String> splitClauses(String header) {
    final List<String> clauses = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < header.length(); i++) {
      final char c = header.charAt(i);
      if (c == '"') {
        depth ^= 1;
      } else if (c == ',' && depth == 0) {
        clauses.add(header.substring(start, i));
        start = i + 1;
      }
    }
    clauses.add(header.substring(start));
    return clauses;
  }
}
