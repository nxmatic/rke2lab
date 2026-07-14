package io.nxmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the manifests scion, run WHERE the scenario lives (this passenger
 * shares the manifests-bdd host loader through the fragment). Unlike the bbox proof — which mocks
 * every collaborator — the manifests scenario resolves the REAL synthesis: manifests-core's DS
 * {@code @Component}s (the {@code ManifestSynthesisService} + {@code NodeEnvOverlayService})
 * activate under SCR and the scenario drives them. So this passenger registers ONLY the mock {@link
 * RunGate} (the one collaborator the host, not SCR, publishes in prod) into the SAME registry the
 * scenario resolves from, then plays {@link ManifestsBddScenarios#run} (the production front-door)
 * with a known activation facet and asserts on the harvested runbook.
 *
 * <p>The assertion is the whole point of the chantier: given the operator's facet, the scenario's
 * WHEN stage — the transposition of {@code HostSlotManifest.Builder.policy()} — derives the policy,
 * synthesises, and writes the overlay, and the runbook plays GREEN. That is the control-plane
 * policy genuinely reactivated INSIDE synthesis, proven end-to-end, not merely compiled.
 */
public class ManifestSynthesisScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void the_scion_synthesizes_from_the_activation_facet() throws Exception {
    // The operator's usual posture (everything on except mesh, debug off) — a complete facet, the
    // same shape a sower plucks from Pulumi.dev.yaml.
    final RecordingCellar cellar = new RecordingCellar();
    final String envelope =
        playWith(cultivatingGate(true), ManifestsRunbookInput.defaults(), cellar);
    final ReportModel runbook = rebuild(envelope);

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "the facet was translated, synthesised, and the overlay written — the policy plays green");

    // I3: the scion PUBLISHED its staging entry of the host-manifest family for the replica it
    // materialised — one store at the host-staging coordinate, carrying the per-file checksums.
    assertEquals(1, cellar.stored.size(), "the scion published its staging entry once");
    final SeedEnvelope published = cellar.stored.get(0);
    assertEquals(
        "host-staging", published.coordinate(), "published at the host-staging coordinate");
    final JsonNode entry = CODEC.decode(published.payload());
    assertFalse(
        entry.path("checksums").isEmpty(),
        "the staging entry carries the synthesised tree's per-file checksums");
    assertEquals(
        "manifests-synthesis",
        entry.path("provenance").asText(),
        "the staging entry names its provenance");
  }

  /**
   * Register the mock {@link RunGate} plus the ambient {@link Cellar} + {@link Parcel} into THIS
   * bundle's registry (the synthesis + overlay services are real SCR components, already
   * published), play the scenario in-container through the front-door with the given facet, and
   * return its serialized envelope. Registrations are removed in the {@code finally} so the shared
   * framework does not leak across tests.
   */
  private static String playWith(RunGate gate, ManifestsRunbookInput facet, Cellar cellar)
      throws Exception {
    final BundleContext context =
        FrameworkUtil.getBundle(ManifestsBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(Cellar.class, cellar, new Hashtable<>()));
    registrations.add(
        context.registerService(Parcel.class, new Parcel("bioskop", "dev"), new Hashtable<>()));
    try {
      return ManifestsBddScenarios.run(facet);
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** A neutral cellar recording every store — proves the scion published its host-manifest. */
  private static final class RecordingCellar implements Cellar {
    final List<SeedEnvelope> stored = new ArrayList<>();

    @Override
    public void store(Parcel parcel, SeedEnvelope vegetal) {
      stored.add(vegetal);
    }

    @Override
    public List<SeedEnvelope> fetch(Parcel parcel) {
      return List.of();
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of(parcel);
    }
  }

  /** The ambient run condition the scion reads — cultivating (live) or surveying (dry-run). */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /**
   * Rebuild a host-realm {@link ReportModel} from the front-door's serialized envelope: read the
   * {@code runbook} field with the host's own jackson, then round it through {@link
   * ScenarioJsonReader} into a model of THIS realm. No jGiven type crosses live — the envelope is
   * flat JSON.
   */
  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("manifest-synthesis-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }
}
