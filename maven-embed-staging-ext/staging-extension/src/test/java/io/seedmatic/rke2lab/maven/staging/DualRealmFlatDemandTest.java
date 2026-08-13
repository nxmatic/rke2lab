package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class DualRealmFlatDemandTest {

  private static final String CARRIER_GA = "g:a";
  private static final String CARRIER_PACKAGE = "io.seedmatic.rke2lab.incus.ingress";

  /**
   * A {@code type=dual-realm} carrier {@code g:a} exporting {@code exportHeader} (headers only).
   */
  private static ResolvedBundle carrierExporting(String exportHeader) {
    return new ResolvedBundle(
        "g",
        "a",
        "1",
        Optional.empty(),
        Optional.of("io.seedmatic.rke2lab.incus.ingress"),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exportHeader),
        false,
        false);
  }

  /**
   * A flat-realm class {@code binaryName} declaring a field of {@code fieldTypeDescriptor} (or
   * none).
   */
  private static ResolvedBundle.ClassEntry flatClass(
      String binaryName, String fieldTypeDescriptor) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName, null, "java/lang/Object", null);
    if (fieldTypeDescriptor != null) {
      cw.visitField(Opcodes.ACC_PRIVATE, "ref", fieldTypeDescriptor, null, null).visitEnd();
    }
    cw.visitEnd();
    return new ResolvedBundle.ClassEntry(binaryName, cw.toByteArray());
  }

  @Test
  void aCarrierAFlatClassReferencesIsDemanded() {
    // A genuine host consumer holds an InstanceGrowPlan field → the flat copy is materialised.
    final Set<String> demanded =
        DualRealmFlatDemand.flatReferencedGas(
            List.of(carrierExporting("io.seedmatic.rke2lab.incus.ingress;version=1.0.0")),
            List.of(
                flatClass(
                    "io/seedmatic/rke2lab/host/HostStage",
                    "Lio/seedmatic/rke2lab/incus/ingress/InstanceGrowPlan;")));
    assertEquals(Set.of(CARRIER_GA), demanded, "a flat consumer → the carrier is kept flat");
  }

  @Test
  void aCarrierNoFlatClassReferencesFoldsOsgiOnly() {
    // Flat classes exist but none touches the carrier's package → no flat copy, fold OSGi-only.
    final Set<String> demanded =
        DualRealmFlatDemand.flatReferencedGas(
            List.of(carrierExporting("io.seedmatic.rke2lab.incus.ingress;version=1.0.0")),
            List.of(
                flatClass("io/seedmatic/rke2lab/host/HostStage", "Ljava/lang/String;"),
                flatClass("io/seedmatic/rke2lab/host/Other", null)));
    assertTrue(demanded.isEmpty(), "no flat consumer → the carrier folds OSGi-only");
  }

  @Test
  void theCarriersOwnClassIsNotAFlatConsumer() {
    // A class IN the exported package references it trivially; that must NOT keep it flat.
    final Set<String> demanded =
        DualRealmFlatDemand.flatReferencedGas(
            List.of(carrierExporting("io.seedmatic.rke2lab.incus.ingress;version=1.0.0")),
            List.of(
                flatClass(
                    "io/seedmatic/rke2lab/incus/ingress/InstanceGrowPlan",
                    "Lio/seedmatic/rke2lab/incus/ingress/BootstrapPaths;")));
    assertTrue(demanded.isEmpty(), "a package referencing itself is not an outside flat consumer");
  }

  @Test
  void aCarrierThatExportsNothingOfOursIsIgnored() {
    // ourExportedPackages() empty → the flat-vs-OSGi split question does not arise.
    final Set<String> demanded =
        DualRealmFlatDemand.flatReferencedGas(
            List.of(carrierExporting("org.cdk8s;version=2.70.76")),
            List.of(flatClass("io/seedmatic/rke2lab/host/HostStage", null)));
    assertTrue(demanded.isEmpty(), "a carrier exporting only third-party packages is out of scope");
    assertTrue(CARRIER_PACKAGE.startsWith("io.seedmatic"), "sanity: our-root marker");
  }
}
