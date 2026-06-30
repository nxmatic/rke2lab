package io.nxmatic.rke2lab.maven.staging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The build-time guard against a package living in BOTH realms at once (the {@code
 * DUPLICATE_REALM_CLASS} law). A staged bundle's exported package is loaded by that bundle's
 * classloader; the same package present FLAT in the host uber-jar is loaded by the application
 * (JCL) classloader. Two copies of one class across two realms is the loader-constraint collision
 * that surfaces as a {@code LinkageError} the moment an instance crosses the seam — exactly the
 * jackson-{@code JsonNode} failure world-exchange Option B fixed, frozen here so it cannot recur by
 * any other route.
 *
 * <p>Built from the set of packages served flat (the exec's own + the shaded-flat tail + the
 * host-flat {@code system.packages.extra}); reports each staged-bundle export that ALSO appears in
 * it. The check is reversed from a seam-centric one on purpose: it walks the whole realm, so it
 * catches a duplication however it arose — a seam import is only one path to it. See
 * docs/architecture/osgi/staging-gates-governance-spec.adoc § DUPLICATE_REALM_CLASS.
 */
final class DuplicateRealmClass {

  private final Set<String> flatPackages;
  private final Set<String> seamSurface;

  DuplicateRealmClass(Set<String> flatPackages, Set<String> seamSurface) {
    this.flatPackages = flatPackages;
    this.seamSurface = seamSurface;
  }

  /**
   * The packages a staged bundle exports that ALSO live flat AND appear on a seam — each a
   * dangerous cross-realm duplication (a type from it can cross the seam). A flat∧staged package
   * absent from every seam cannot cross, so it is exempt — the derived realm-library case.
   */
  List<String> violations(ResolvedBundle stagedBundle) {
    final List<String> lines = new ArrayList<>();
    for (String exported : stagedBundle.exports().names()) {
      if (flatPackages.contains(exported) && seamSurface.contains(exported)) {
        lines.add(exported);
      }
    }
    return lines;
  }
}
