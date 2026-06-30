package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import org.junit.jupiter.api.Test;

class StagingClosureTest {

  private static ResolvedBundle bundle(
      String g, String a, String type, String imports, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        null,
        g + "." + a,
        EmbedCapability.of(OsgiHeader.parse("io.nxmatic.rke2lab.embed;type=" + type)),
        OsgiHeader.parse(imports),
        OsgiHeader.parse(exports),
        false);
  }

  private static ResolvedBundle thirdParty(String g, String a, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        null,
        g + "." + a,
        null,
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
