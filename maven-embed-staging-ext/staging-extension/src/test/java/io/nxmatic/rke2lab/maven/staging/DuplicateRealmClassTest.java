package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DuplicateRealmClassTest {

  /** A staged bundle exporting the given packages (no jar file — the gate reads only exports). */
  private static ResolvedBundle exporting(String exportHeader) {
    return new ResolvedBundle(
        "g",
        "a",
        "1",
        null,
        "io.nxmatic.rke2lab.bundle",
        null,
        OsgiHeader.parse(null),
        OsgiHeader.parse(exportHeader),
        false);
  }

  @Test
  void aFlatAndStagedPackageAbsentFromTheSeamIsExempt() {
    // jackson is flat AND staged, but no seam exports it → safe, exempt (the derived rule).
    final DuplicateRealmClass gate =
        new DuplicateRealmClass(
            Set.of("com.fasterxml.jackson.databind"),
            /*seamSurface*/ Set.of("io.nxmatic.rke2lab.world.gateway.port"));
    assertTrue(
        gate.violations(exporting("com.fasterxml.jackson.databind;version=2.22.0")).isEmpty(),
        "flat∧staged is safe when the package is not on any seam");
  }

  @Test
  void aFlatAndStagedPackagePRESENTOnTheSeamIsAViolation() {
    // if a seam exported jackson, a type could cross → the duplication is dangerous → flagged.
    final DuplicateRealmClass gate =
        new DuplicateRealmClass(
            Set.of("com.fasterxml.jackson.databind"), Set.of("com.fasterxml.jackson.databind"));
    final List<String> v =
        gate.violations(exporting("com.fasterxml.jackson.databind;version=2.22.0"));
    assertEquals(1, v.size(), "a seam carrying the package loses the exemption");
    assertEquals("com.fasterxml.jackson.databind", v.get(0));
  }

  @Test
  void aPackageOnTheSeamButNotFlatIsClean() {
    // A seam exports a package, but it is NOT flat in the host → no flat∧staged duplication.
    // Exercises the flatPackages branch of the AND: not flat ⇒ no violation regardless of the seam.
    final DuplicateRealmClass gate =
        new DuplicateRealmClass(
            Set.of("com.fasterxml.jackson.databind"),
            Set.of("io.nxmatic.rke2lab.world.gateway.port"));
    assertTrue(
        gate.violations(exporting("io.nxmatic.rke2lab.world.gateway.port;version=1.0.0")).isEmpty(),
        "a seam-only package that is not flat is the normal OSGi path, not a duplication");
  }
}
