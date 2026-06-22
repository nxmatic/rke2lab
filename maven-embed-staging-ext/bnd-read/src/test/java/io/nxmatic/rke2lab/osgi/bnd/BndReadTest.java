package io.nxmatic.rke2lab.osgi.bnd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of the extracted bnd-header reading — pure string→value parsing, no framework. These
 * are the cases that JUSTIFY the code existing (the JDK and osgi.core give none of them): a comma
 * inside a quoted version range is not a clause separator, an import mirrors to its version lower
 * bound, and the embed capability decodes the seam discriminator.
 */
class BndReadTest {

  @Test
  void splitClausesKeepsAQuotedVersionRangeCommaTogether() {
    // The whole reason splitClauses exists: the comma inside "[1.5,2)" must NOT split the clause.
    final List<Clause> clauses =
        OsgiHeader.parse(
                "org.osgi.service.component;version=\"[1.5,2)\","
                    + "org.osgi.util.promise;version=\"[1.1,2)\"")
            .clauses();
    assertEquals(
        2, clauses.size(), "two clauses, not three (the version-range comma is not a split)");
    assertEquals("org.osgi.service.component", clauses.get(0).name());
    assertEquals("org.osgi.util.promise", clauses.get(1).name());
  }

  @Test
  void packageNamesStripsAttributes() {
    assertEquals(
        java.util.Set.of("foo.bar", "baz.qux"),
        OsgiHeader.parse("foo.bar;version=1.0;resolution:=optional,baz.qux").names());
  }

  @Test
  void mirrorImportsNarrowsToVersionLowerBound() {
    // An Import-Package range becomes a system-export pinned to its lower bound so the importer's
    // range is satisfied without re-stating the whole range.
    assertEquals(
        java.util.Set.of("org.osgi.service.component;version=1.5"),
        OsgiHeader.parse("org.osgi.service.component;version=\"[1.5,2)\"").asSystemExports());
  }

  @Test
  void mirrorImportsKeepsAVersionlessImportBare() {
    assertEquals(
        java.util.Set.of("com.example.flat"),
        OsgiHeader.parse("com.example.flat").asSystemExports());
  }

  @Test
  void clauseParseReadsAttributesAndStripsDirectiveColon() {
    final Clause clause =
        Clause.parse("org.osgi.service.component;version=\"[1.5,2)\";resolution:=mandatory");
    assertEquals("org.osgi.service.component", clause.name());
    assertEquals("1.5", clause.versionLowerBound());
    // A directive (key:=value) keeps its bare key; the value has quotes stripped.
    assertEquals("mandatory", clause.attributes().get("resolution"));
  }

  @Test
  void embedCapabilityDecodesAModelAsDomainNotSeam() {
    final EmbedCapability embed =
        EmbedCapability.of(OsgiHeader.parse("io.nxmatic.rke2lab.embed;type=model;model=manifests"));
    assertEquals(EmbedCapability.TYPE_MODEL, embed.type());
    assertTrue(embed.isDomain(), "model loads on the bundle side");
    assertFalse(embed.isSeam());
  }

  @Test
  void embedCapabilityDecodesASeam() {
    final EmbedCapability embed =
        EmbedCapability.of(OsgiHeader.parse("io.nxmatic.rke2lab.embed;type=seam"));
    assertTrue(embed.isSeam(), "a -port is the seam: system-exported, never installed");
    assertFalse(embed.isDomain());
  }

  @Test
  void embedCapabilityAbsentWhenNamespaceNotDeclared() {
    // A jar with some other capability but no embed namespace is not ours to embed.
    assertNull(EmbedCapability.of(OsgiHeader.parse("osgi.service;objectClass=\"com.acme.Foo\"")));
    assertNull(EmbedCapability.of(OsgiHeader.parse("")));
  }
}
