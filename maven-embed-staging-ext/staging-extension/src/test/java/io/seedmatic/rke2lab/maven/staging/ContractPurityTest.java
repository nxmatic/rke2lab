package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * The contract of the contract-purity guard — the law that gives the {@code type=contract} category
 * its meaning. Each case synthesises a {@code .class} carrying exactly the metadata the guard reads
 * (access flags + {@code PermittedSubclasses}), packs it into a jar with an {@code Export-Package}
 * manifest, and asserts what {@link ContractPurity#violations()} reports. The bytecode need not be
 * a loadable class — the guard reads metadata only, which is the whole point of using ASM over
 * reflection (the real records reference jackson/systemd types this realm cannot link).
 */
class ContractPurityTest {

  // Under OUR root: the gates govern only io.seedmatic.rke2lab.* (a carrier's foreign exports are
  // out
  // of jurisdiction — see ResolvedBundle#ourExportedPackages). PKG is the dotted Export-Package
  // value; PATH is its binary form for the synthesised .class entries.
  private static final String PKG = "io.seedmatic.rke2lab.ex";
  private static final String PATH = PKG.replace('.', '/');

  @Test
  void recordEnumAndSealedAdtRootAreAllContract(@TempDir File dir) throws IOException {
    final File jar =
        jar(
            dir,
            classNode(
                PATH + "/AReccord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode(PATH + "/AnEnum", Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, "java/lang/Enum"),
            sealedInterface(PATH + "/AnAdtRoot", PATH + "/AReccord"));
    assertTrue(
        purity(jar).violations().isEmpty(), "record, enum, sealed ADT root are all contract types");
  }

  @Test
  void aServiceInterfaceIsAllowed(@TempDir File dir) throws IOException {
    // Widened from record-purity: a plain (non-sealed) service interface is a legitimate contract
    // face — the door a consumer resolves from the registry. The impl lives in the domain's -core.
    final File jar =
        jar(
            dir,
            classNode(
                PATH + "/AContract",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "java/lang/Object"));
    assertTrue(
        purity(jar).violations().isEmpty(), "a service interface is a legitimate contract face");
  }

  @Test
  void aConcreteClassIsAViolation(@TempDir File dir) throws IOException {
    // An implementation belongs in the domain's -core, never on the contract face.
    final File jar =
        jar(dir, classNode(PATH + "/Behavior", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertEquals(List.of(PKG + ".Behavior"), purity(jar).violations());
  }

  @Test
  void aNestedTypeIsSkipped(@TempDir File dir) throws IOException {
    // A record's nested Builder (a plain class) is its own construction contract — not policed.
    final File jar =
        jar(
            dir,
            classNode(PATH + "/Coord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode(PATH + "/Coord$Builder", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertTrue(
        purity(jar).violations().isEmpty(), "a nested $ type is part of its enclosing record");
  }

  @Test
  void aTypeInANonExportedPackageIsSkipped(@TempDir File dir) throws IOException {
    // The guard polices the SEAM (exported packages); a non-exported package is private business.
    final File jar =
        jar(
            dir,
            PKG, // only PKG is exported
            classNode(
                PATH + "/AReccord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode("hidden/Behavior", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertTrue(
        purity(jar).violations().isEmpty(), "a class in a non-exported package is not on the seam");
  }

  @Test
  void aForeignExportedPackageIsOutOfJurisdiction(@TempDir File dir) throws IOException {
    // A carrier (e.g. manifests-cdk8s) re-exporting a third-party closure exports packages that are
    // NOT ours: org.cdk8s here. The gates govern only io.seedmatic.rke2lab.* — a plain foreign
    // class
    // on the exported surface is not a purity violation, it is simply not our code.
    final File jar =
        jar(
            dir,
            "org.cdk8s",
            classNode("org/cdk8s/ApiObject", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertTrue(
        purity(jar).violations().isEmpty(),
        "a re-exported third-party package is outside the gate's jurisdiction");
  }

  /** A {@link ContractPurity} of a {@code type=contract} bundle read from the synthesised jar. */
  private static ContractPurity purity(File jar) {
    return ResolvedBundle.read("g", "a", "1", jar).contractPurity();
  }

  private record ClassNode(String binaryName, byte[] bytes) {}

  /** A class file carrying only the access flags + super the guard reads — not a loadable class. */
  private static ClassNode classNode(String binaryName, int access, String superName) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, access, binaryName, null, superName, null);
    cw.visitEnd();
    return new ClassNode(binaryName, cw.toByteArray());
  }

  /** A sealed interface (an ADT root): an interface that names a permitted subclass. */
  private static ClassNode sealedInterface(String binaryName, String permitted) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
        binaryName,
        null,
        "java/lang/Object",
        null);
    cw.visitPermittedSubclass(permitted);
    cw.visitEnd();
    return new ClassNode(binaryName, cw.toByteArray());
  }

  /** Pack the nodes into a jar exporting {@link #PKG}. */
  private static File jar(File dir, ClassNode... nodes) throws IOException {
    return jar(dir, PKG, nodes);
  }

  /** Pack the nodes into a jar whose manifest exports {@code exportedPackage}. */
  private static File jar(File dir, String exportedPackage, ClassNode... nodes) throws IOException {
    final Manifest manifest = new Manifest();
    final Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Bundle-SymbolicName", "ex.contract");
    main.putValue("Export-Package", exportedPackage);
    main.putValue("Provide-Capability", "io.seedmatic.rke2lab.embed; type=contract");

    final File jar = new File(dir, "contract.jar");
    try (JarOutputStream out = new JarOutputStream(new java.io.FileOutputStream(jar), manifest)) {
      for (ClassNode node : nodes) {
        out.putNextEntry(new ZipEntry(node.binaryName() + ".class"));
        out.write(node.bytes());
        out.closeEntry();
      }
    }
    return jar;
  }
}
