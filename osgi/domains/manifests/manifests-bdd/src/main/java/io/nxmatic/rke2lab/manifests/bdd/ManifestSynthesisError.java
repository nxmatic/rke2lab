package io.nxmatic.rke2lab.manifests.bdd;

import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.doctor.contract.Symptomatic;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import java.util.Map;

/**
 * The manifest synthesis produced an incomplete result — fewer domains than enabled, no file
 * written, no units processed, or a missing publish var. {@link Symptomatic}: carries the {@link
 * ManifestSynthesisResult} as a typed member and the {@link Gap} that failed, so a consumer sees
 * exactly which post-condition broke and against what result.
 */
public final class ManifestSynthesisError extends AssertionError implements Symptomatic {

  /** Which synthesis post-condition failed. */
  public enum Gap {
    DOMAIN_COUNT_SHORT,
    MANIFEST_FILE_MISSING,
    NO_UNITS_PROCESSED,
    MISSING_PUBLISH_VAR
  }

  private final transient ManifestSynthesisResult result;
  private final Gap gap;

  public ManifestSynthesisError(String message, Gap gap, ManifestSynthesisResult result) {
    super(message);
    this.gap = gap;
    this.result = result;
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.SYNTHESIS_INCOMPLETE;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of("gap", gap.name());
  }

  public ManifestSynthesisResult result() {
    return result;
  }

  public Gap gap() {
    return gap;
  }
}
