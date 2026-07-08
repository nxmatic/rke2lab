package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StagingClosureTest {

  private static ResolvedBundle bundle(
      String g, String a, String type, String imports, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(g + "." + a),
        Optional.ofNullable(
            EmbedCapability.of(OsgiHeader.parse("io.nxmatic.rke2lab.embed;type=" + type))),
        OsgiHeader.parse(imports),
        OsgiHeader.parse(exports),
        false);
  }

  private static ResolvedBundle thirdParty(String g, String a, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(g + "." + a),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exports),
        false);
  }

  /** A third-party bundle carrying an explicit symbolic name (e.g. a boot-stack member). */
  private static ResolvedBundle named(String g, String a, String symbolicName, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(symbolicName),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exports),
        false);
  }

  @Test
  void aThirdPartyBundleAModelImportsIsStagedAsRealmLibrary() {
    // doctor-core (model) imports com.fasterxml.jackson.databind; jackson-databind (third-party
    // OSGi bundle) exports it. The realm library MUST be staged (its own OSGi copy) even though the
    // host serves it flat too.
    final ResolvedBundle model =
        bundle(
            "io.nxmatic.rke2lab",
            "doctor-core",
            "model",
            /*imports*/ "com.fasterxml.jackson.databind",
            /*exports*/ "io.nxmatic.rke2lab.doctor");
    final ResolvedBundle jackson =
        thirdParty(
            "com.fasterxml.jackson.core",
            "jackson-databind",
            /*exports*/ "com.fasterxml.jackson.databind");

    final StagingClosure closure = StagingClosure.compute(List.of(model, jackson));

    assertTrue(
        closure.stagedGas().contains("com.fasterxml.jackson.core:jackson-databind"),
        "the realm library is staged as a bundle");
    assertTrue(
        closure.realmLibraryGas().contains("com.fasterxml.jackson.core:jackson-databind"),
        "it is classified a realm library (flat AND staged)");
  }

  @Test
  void aRealmLibraryIsNotStagedWhenTheBootStackAlreadyProvidesItsPackage() {
    // manifests-core (model) imports org.slf4j; slf4j-api (third-party) exports it — but
    // pax-logging-api (boot-stack) ALSO exports org.slf4j in-framework. Staging slf4j-api would add
    // a second in-framework exporter and break slf4j 2.x resolution, so it must NOT be a realm
    // library; it stays host-flat only.
    final ResolvedBundle model =
        bundle(
            "io.nxmatic.rke2lab",
            "manifests-core",
            "model",
            /*imports*/ "org.slf4j",
            /*exports*/ "io.nxmatic.rke2lab.manifests");
    final ResolvedBundle slf4j = thirdParty("org.slf4j", "slf4j-api", /*exports*/ "org.slf4j");
    final ResolvedBundle pax =
        named(
            "org.ops4j.pax.logging",
            "pax-logging-api",
            "org.ops4j.pax.logging.pax-logging-api",
            /*exports*/ "org.slf4j");

    final StagingClosure closure = StagingClosure.compute(List.of(model, slf4j, pax));

    assertTrue(
        !closure.realmLibraryGas().contains("org.slf4j:slf4j-api"),
        "slf4j-api is not a realm library — pax (boot-stack) already provides org.slf4j");
    assertTrue(
        !closure.stagedGas().contains("org.slf4j:slf4j-api"),
        "slf4j-api is not staged — staging it would add a second org.slf4j exporter");
  }

  @Test
  void aSeamPackageIsNotStagedEvenIfAModelImportsIt() {
    final ResolvedBundle model =
        bundle(
            "io.nxmatic.rke2lab",
            "doctor-core",
            "model",
            "io.nxmatic.rke2lab.world.gateway.port",
            "io.nxmatic.rke2lab.doctor");
    final ResolvedBundle seam =
        bundle(
            "io.nxmatic.rke2lab",
            "world-gateway",
            "seam",
            null,
            "io.nxmatic.rke2lab.world.gateway.port");
    final StagingClosure closure = StagingClosure.compute(List.of(model, seam));
    assertTrue(closure.realmLibraryGas().isEmpty(), "a seam is host-flat, never a realm library");
  }
}
