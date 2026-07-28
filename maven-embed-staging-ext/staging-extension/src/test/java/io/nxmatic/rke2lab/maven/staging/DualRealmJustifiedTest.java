package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class DualRealmJustifiedTest {

  private static final String CARRIER_PACKAGE = "io.nxmatic.rke2lab.incus.ingress";

  /** A {@code type=dual-realm} carrier exporting {@link #CARRIER_PACKAGE} (headers only). */
  private static ResolvedBundle carrierExporting(String exportHeader) {
    return new ResolvedBundle(
        "g",
        "a",
        "1",
        Optional.empty(),
        Optional.of("io.nxmatic.rke2lab.incus.ingress"),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exportHeader),
        false,
        false);
  }

  /**
   * A flat-realm class {@code binaryName} that declares a field of type {@code fieldType} (or
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
  void aCarrierAFlatClassReferencesIsJustified() {
    // A genuine host importer holds an InstanceGrowPlan field → the second copy earns its keep.
    final DualRealmJustified gate =
        new DualRealmJustified(
            List.of(
                flatClass(
                    "io/nxmatic/rke2lab/host/HostStage",
                    "Lio/nxmatic/rke2lab/incus/ingress/InstanceGrowPlan;")));
    assertTrue(
        gate.violations(carrierExporting("io.nxmatic.rke2lab.incus.ingress;version=1.0.0"))
            .isEmpty(),
        "a flat/host class references the exported package → dual-realm split is justified");
  }

  @Test
  void aCarrierNoFlatClassReferencesIsUnjustified() {
    // Flat classes exist but none touches the carrier's package → the flat copy is dead weight.
    final DualRealmJustified gate =
        new DualRealmJustified(
            List.of(
                flatClass("io/nxmatic/rke2lab/host/HostStage", "Ljava/lang/String;"),
                flatClass("io/nxmatic/rke2lab/host/Other", null)));
    final List<String> v =
        gate.violations(carrierExporting("io.nxmatic.rke2lab.incus.ingress;version=1.0.0"));
    assertEquals(1, v.size(), "no host importer → one violation");
    assertTrue(v.get(0).contains(CARRIER_PACKAGE), "the line names the unused exported package");
    assertTrue(v.get(0).contains("fold OSGi-only"), "the line prescribes the fix");
  }

  @Test
  void theCarriersOwnClassIsNotAHostImporter() {
    // A class IN the exported package references it trivially; that must NOT count as
    // justification.
    final DualRealmJustified gate =
        new DualRealmJustified(
            List.of(
                flatClass(
                    "io/nxmatic/rke2lab/incus/ingress/InstanceGrowPlan",
                    "Lio/nxmatic/rke2lab/incus/ingress/BootstrapPaths;")));
    assertEquals(
        1,
        gate.violations(carrierExporting("io.nxmatic.rke2lab.incus.ingress;version=1.0.0")).size(),
        "a package referencing itself is not an outside host importer → still unjustified");
  }

  @Test
  void aCarrierThatExportsNothingOfOursIsClean() {
    // ourExportedPackages() empty → the flat-vs-OSGi split question does not arise.
    final DualRealmJustified gate =
        new DualRealmJustified(List.of(flatClass("io/nxmatic/rke2lab/host/HostStage", null)));
    assertTrue(
        gate.violations(carrierExporting("org.cdk8s;version=2.70.76")).isEmpty(),
        "a carrier exporting only third-party packages is out of jurisdiction");
  }
}
