package io.seedmatic.rke2lab.maven.staging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The build-time guard of the host↔OSGi boundary (the {@code REALM_BOUNDARY} law): a class may
 * reference only types reachable in its OWN classloader realm. Constructed for one realm with its
 * forbidden set (packages a class in this realm cannot load) and its own/visible packages; reports
 * each class that references a forbidden package. Unlike the export-surface gates it reads method
 * bodies (via {@link ReferencedTypes}) — the drift it catches is {@code Severity.parse()}, an
 * invokestatic in a body. The realm label is carried into each line so a violation is
 * auto-attributed (a {@code flat}-realm line is a host/seam leak; a bundle-realm line is an
 * OSGi-internal leak). See docs/architecture/osgi/staging-gates-governance-spec.adoc §
 * REALM_BOUNDARY.
 */
final class RealmBoundary {

  private final String realmLabel;
  private final Set<String> forbiddenPackages;
  private final Set<String> visiblePackages;

  RealmBoundary(String realmLabel, Set<String> forbiddenPackages, Set<String> visiblePackages) {
    this.realmLabel = realmLabel;
    this.forbiddenPackages = forbiddenPackages;
    this.visiblePackages = visiblePackages;
  }

  /** The leak lines for one class: each forbidden package it references and cannot see. */
  List<String> violations(String binaryName, byte[] classfile) {
    final String simple = simpleName(binaryName);
    final List<String> lines = new ArrayList<>();
    for (String referenced : ReferencedTypes.in(classfile)) {
      if (visiblePackages.contains(referenced)) {
        continue; // reachable in this realm — fine.
      }
      if (forbiddenPackages.contains(referenced)) {
        lines.add(realmLabel + " " + simple + " references " + referenced);
      }
    }
    return lines;
  }

  private static String simpleName(String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    return slash < 0 ? binaryName : binaryName.substring(slash + 1);
  }
}
