package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ResolvedBundleClassEntriesTest {

  @Test
  void readsEveryClassEntryFromTheJar(@TempDir File dir) throws IOException {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        "io/seedmatic/rke2lab/ex/Foo",
        null,
        "java/lang/Object",
        null);
    cw.visitEnd();
    final Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    final File jar = new File(dir, "x.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      out.putNextEntry(new ZipEntry("io/seedmatic/rke2lab/ex/Foo.class"));
      out.write(cw.toByteArray());
      out.closeEntry();
    }
    final var entries = ResolvedBundle.read("g", "a", "1", jar).classEntries();
    assertTrue(
        entries.stream().anyMatch(e -> e.binaryName().equals("io/seedmatic/rke2lab/ex/Foo")));
  }
}
