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
  void aPackageExportedAndAlsoFlatIsADuplication() {
    final DuplicateRealmClass gate =
        new DuplicateRealmClass(Set.of("org.cdk8s", "io.nxmatic.host"));
    final List<String> v = gate.violations(exporting("org.cdk8s;version=1.0.0"));
    assertEquals(1, v.size(), "the package lives flat AND is bundle-exported → one duplication");
    assertEquals("org.cdk8s", v.get(0));
  }

  @Test
  void aPackageExportedButNotFlatIsClean() {
    final DuplicateRealmClass gate = new DuplicateRealmClass(Set.of("io.nxmatic.host"));
    final List<String> v =
        gate.violations(exporting("io.nxmatic.rke2lab.doctor.port;version=1.0.0"));
    assertTrue(v.isEmpty(), "a bundle-only export (not present flat) is the normal single realm");
  }

  @Test
  void slf4jIsExemptEvenWhenBothFlatAndExported() {
    // org.slf4j is host-flat AND provided to bundles by pax — the one deliberate shared provider
    // (BootPlanner drops it from system.packages.extra so it never collides). Not a duplication.
    final DuplicateRealmClass gate = new DuplicateRealmClass(Set.of("org.slf4j"));
    final List<String> v = gate.violations(exporting("org.slf4j;version=2.0.0"));
    assertTrue(v.isEmpty(), "org.slf4j is the documented shared exemption");
  }
}
