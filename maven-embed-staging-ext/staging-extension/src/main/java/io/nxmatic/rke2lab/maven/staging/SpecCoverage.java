package io.nxmatic.rke2lab.maven.staging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The build-time spec-coverage check OF a {@link ResolvedBundle} — the law that keeps the docs in
 * step with the code: every type a bundle exports must be named in some {@code docs/} architecture
 * spec, OR carry {@code @Transitional} (a traced migration whose spec describes the successor). A
 * type in neither is a {@code SPEC_COVERAGE} violation (code-out-of-spec).
 *
 * <p>This guard only DETECTS the violations; how they are reported (fail, list, or ignore) is the
 * {@link GovernanceReader}'s call, read from {@code @GovernedBy(SPEC_COVERAGE, level)} on the
 * bundle's package-infos (default {@code ERROR}). The guard stays LOCAL to the bundle it reads — it
 * opens the one jar, reads its exported types, decides — exactly like its twin {@link RecordPurity}
 * reads its own classes; it never groups or cross-references bundles.
 *
 * <p>An INSTANCE reached from its subject ({@link ResolvedBundle#specCoverage(Path)}), not a static
 * helper — navigable back to the bundle it checks, like the rest of this model.
 */
final class SpecCoverage {

  private static final String TRANSITIONAL = "Lio/nxmatic/rke2lab/domain/annotations/Transitional;";

  private final ResolvedBundle bundle;
  private final Path docsDir;

  SpecCoverage(ResolvedBundle bundle, Path docsDir) {
    this.bundle = bundle;
    this.docsDir = docsDir;
  }

  /**
   * The simple names of exported top-level types that are neither named in a {@code docs/} spec nor
   * marked {@code @Transitional} — the {@code SPEC_COVERAGE} violations, INDEPENDENT of how they
   * are reported ({@link ResolvedBundle#governance()} decides the level). Empty when the bundle
   * exports nothing.
   */
  List<String> violations() {
    final Set<String> exportedPackages = bundle.ourExportedPackages();
    if (exportedPackages.isEmpty()) {
      return List.of();
    }
    try (JarFile jar = new JarFile(bundle.file().orElseThrow())) {
      final String docsCorpus = readDocsCorpus();
      final List<String> violations = new ArrayList<>();
      final Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final JarEntry entry = entries.nextElement();
        final String name = entry.getName();
        if (!name.endsWith(".class")
            || name.contains("$")
            || name.endsWith("package-info.class")
            || name.endsWith("module-info.class")) {
          continue;
        }
        final String binary = name.substring(0, name.length() - ".class".length());
        final int slash = binary.lastIndexOf('/');
        final String pkg = slash < 0 ? "" : binary.substring(0, slash).replace('/', '.');
        if (!exportedPackages.contains(pkg)) {
          continue; // a non-exported package is private business, not the published surface.
        }
        final String simple = binary.substring(slash + 1);
        try (var in = jar.getInputStream(entry)) {
          if (hasAnnotation(new ClassReader(in), TRANSITIONAL)) {
            continue; // a traced migration — consistent by reference to its successor spec.
          }
        }
        if (!mentions(docsCorpus, simple)) {
          violations.add(simple);
        }
      }
      return violations;
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read jar " + bundle.ga(), ex);
    }
  }

  /** Whether the class carries an annotation whose descriptor equals {@code descriptor}. */
  private static boolean hasAnnotation(ClassReader reader, String descriptor) {
    final boolean[] found = {false};
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (descriptor.equals(desc)) {
              found[0] = true;
            }
            return null;
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return found[0];
  }

  /**
   * A type is "named in a spec" when its simple name appears as a whole word in the docs corpus.
   */
  private static boolean mentions(String docsCorpus, String simpleName) {
    int from = 0;
    while ((from = docsCorpus.indexOf(simpleName, from)) >= 0) {
      final int end = from + simpleName.length();
      final boolean leftOk = from == 0 || !isWordChar(docsCorpus.charAt(from - 1));
      final boolean rightOk = end >= docsCorpus.length() || !isWordChar(docsCorpus.charAt(end));
      if (leftOk && rightOk) {
        return true;
      }
      from = end;
    }
    return false;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** All {@code .adoc} text under {@code docsDir}, concatenated once — the corpus to search. */
  private String readDocsCorpus() {
    if (docsDir == null || !Files.isDirectory(docsDir)) {
      return "";
    }
    try (Stream<Path> tree = Files.walk(docsDir)) {
      return tree.filter(p -> p.toString().endsWith(".adoc"))
          .map(SpecCoverage::readFile)
          .collect(Collectors.joining("\n"));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read docs tree " + docsDir, ex);
    }
  }

  private static String readFile(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + p, ex);
    }
  }
}
