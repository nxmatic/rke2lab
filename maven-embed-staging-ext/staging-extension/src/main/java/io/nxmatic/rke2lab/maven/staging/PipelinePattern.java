package io.nxmatic.rke2lab.maven.staging;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;

/**
 * The build-time shape check of a fluent-pipeline topic (the {@code PIPELINE_PATTERN} law): a class
 * that participates in the pipeline grammar must {@code implements Topic} AND declare exactly one
 * nature — {@code Topic.Execution}, {@code Topic.Checkpoint}, or {@code Topic.Pipeline}. The nature
 * is a nested TYPE, not a returned enum, so it is visible in the {@code implements} list of the
 * bytecode; a class that carries the bare {@code Topic} marker with no nature (or, defensively,
 * more than one) is out of spec. See docs/architecture/patterns/fluent-pipeline-grammar.adoc.
 *
 * <p>Reads signatures only (the interface list, {@code ClassReader.SKIP_CODE}) over every class of
 * a scanned surface — {@code ClassEntry} list, so the SAME instance serves a bundle jar
 * (manifests's internal topics) and the exec's own {@code target/classes} (seed-master's host
 * topics), exactly as {@link RealmBoundary} runs on both realms. The read-face invariant (a topic
 * reads a produced slot through a {@code Supplier}, never a copied reference) is a separate later
 * increment — it needs call-site stack analysis, not the interface list.
 */
final class PipelinePattern {

  private static final String TOPIC = "io/nxmatic/rke2lab/pipeline/Topic";
  private static final String EXECUTION = "io/nxmatic/rke2lab/pipeline/Topic$Execution";
  private static final String CHECKPOINT = "io/nxmatic/rke2lab/pipeline/Topic$Checkpoint";
  private static final String PIPELINE = "io/nxmatic/rke2lab/pipeline/Topic$Pipeline";

  private PipelinePattern() {}

  /** The out-of-spec lines for one scanned surface: each topic that carries no single nature. */
  static List<String> violations(List<ResolvedBundle.ClassEntry> classes) {
    final List<String> lines = new ArrayList<>();
    for (ResolvedBundle.ClassEntry entry : classes) {
      inspect(entry, lines);
    }
    return lines;
  }

  /**
   * A class is a topic iff its interface list mentions {@code Topic} or any nature. Of those, a
   * valid topic declares EXACTLY one nature; the bare {@code Topic} marker alone (no nature) or two
   * natures is the violation. The contract itself — {@code Topic} and its three nested nature
   * interfaces ({@code Topic$Execution} …), which extend {@code Topic} and so carry it in their own
   * interface list — is the DEFINITION, not a topic, so it is skipped by binary name (pipeline-port
   * IS in the reactor, so they DO reach this scan).
   */
  private static void inspect(ResolvedBundle.ClassEntry entry, List<String> lines) {
    if (entry.binaryName().startsWith(TOPIC)) {
      return; // the Topic contract + its nested nature interfaces — the definition, not a topic.
    }
    final String[] interfaces = new ClassReader(entry.bytes()).getInterfaces();

    boolean marker = false;
    int natures = 0;
    for (String iface : interfaces) {
      switch (iface) {
        case TOPIC -> marker = true;
        case EXECUTION, CHECKPOINT, PIPELINE -> {
          marker = true;
          natures++;
        }
        default -> {}
      }
    }
    if (!marker) {
      return; // not a topic — nothing to say.
    }

    final String simple = simpleName(entry.binaryName());
    if (natures == 0) {
      lines.add(simple + " implements Topic with no nature (Execution/Checkpoint/Pipeline)");
    } else if (natures > 1) {
      lines.add(simple + " declares " + natures + " natures (a topic has exactly one)");
    }
  }

  private static String simpleName(String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    return slash < 0 ? binaryName : binaryName.substring(slash + 1);
  }
}
