package io.nxmatic.rke2lab.maven.staging;

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
 * The contract of the record-purity guard — the law that gives the {@code type=record} category its
 * meaning. Each case synthesises a {@code .class} carrying exactly the metadata the guard reads
 * (access flags + {@code PermittedSubclasses}), packs it into a jar with an {@code Export-Package}
 * manifest, and asserts what {@link RecordPurity#violations()} reports. The bytecode need not be a
 * loadable class — the guard reads metadata only, which is the whole point of using ASM over
 * reflection (the real records reference jackson/systemd types this realm cannot link).
 */
class RecordPurityTest {

  private static final String PKG = "ex";

  @Test
  void recordEnumAndSealedAdtRootAreAllData(@TempDir File dir) throws IOException {
    final File jar =
        jar(
            dir,
            classNode(
                PKG + "/AReccord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode(PKG + "/AnEnum", Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, "java/lang/Enum"),
            sealedInterface(PKG + "/AnAdtRoot", PKG + "/AReccord"));
    assertTrue(
        purity(jar).violations().isEmpty(), "record, enum, sealed ADT root are all pure data");
  }

  @Test
  void aPlainClassIsAViolation(@TempDir File dir) throws IOException {
    final File jar = jar(dir, classNode(PKG + "/Behavior", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertEquals(List.of(PKG + ".Behavior"), purity(jar).violations());
  }

  @Test
  void aNonSealedInterfaceIsAViolation(@TempDir File dir) throws IOException {
    // An interface is data ONLY as a sealed ADT root; a plain contract interface is behavior.
    final File jar =
        jar(
            dir,
            classNode(
                PKG + "/AContract",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "java/lang/Object"));
    assertEquals(List.of(PKG + ".AContract"), purity(jar).violations());
  }

  @Test
  void aNestedTypeIsSkipped(@TempDir File dir) throws IOException {
    // A record's nested Builder (a plain class) is its own construction contract — not policed.
    final File jar =
        jar(
            dir,
            classNode(PKG + "/Coord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode(PKG + "/Coord$Builder", Opcodes.ACC_PUBLIC, "java/lang/Object"));
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
                PKG + "/AReccord", Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, "java/lang/Record"),
            classNode("hidden/Behavior", Opcodes.ACC_PUBLIC, "java/lang/Object"));
    assertTrue(
        purity(jar).violations().isEmpty(), "a class in a non-exported package is not on the seam");
  }

  /** A {@link RecordPurity} of a {@code type=record} bundle read from the synthesised jar. */
  private static RecordPurity purity(File jar) {
    return ResolvedBundle.read("g", "a", "1", jar).recordPurity();
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
    main.putValue("Bundle-SymbolicName", "ex.records");
    main.putValue("Export-Package", exportedPackage);
    main.putValue("Provide-Capability", "io.nxmatic.rke2lab.embed; type=record");

    final File jar = new File(dir, "records.jar");
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
