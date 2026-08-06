package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.doctor.contract.Symptomatic;
import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import java.util.Map;

/**
 * The node-base image build (nix realise → import → alias) failed. {@link Symptomatic}: carries the
 * {@link ImageBuildRequest} that failed as a typed member and the edge exception as the cause, so a
 * consumer sees which project/artifact-dir was targeted and the underlying reason without parsing
 * the message.
 */
public final class IncusImageBuildError extends AssertionError implements Symptomatic {

  private final transient ImageBuildRequest request;

  public IncusImageBuildError(ImageBuildRequest request, Throwable cause) {
    super("incus image build failed: " + cause.getMessage(), cause);
    this.request = request;
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.IMAGE_BUILD_FAILED;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of(
        "incusProject", request.incusProject(), "artifactDir", request.localArtifactDir());
  }

  public ImageBuildRequest request() {
    return request;
  }
}
