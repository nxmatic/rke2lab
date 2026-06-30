package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerSource;
import io.nxmatic.rke2lab.pulumi.edge.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.pulumi.edge.testkit.GrpcChannelNoiseCapture;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The full append-then-read round-trip across the writer and {@link InterventionLedgerSource}. It
 * drives a real Pulumi inline {@code up()}, so it is tagged {@code host} + {@code live} (excluded
 * from the default run) and registers {@link GrpcChannelNoiseCapture} to swallow the benign gRPC
 * channel noise that the inline deployment leaves at garbage-collection time.
 */
@Tag("host")
@Tag("live")
final class InterventionLedgerRoundTripLiveTest {

  @RegisterExtension
  static final GrpcChannelNoiseCapture GRPC_NOISE = new GrpcChannelNoiseCapture();

  @Test
  void roundTripsAppendedInterventions(@TempDir Path backendDir) throws Exception {
    // Append two interventions with different times via the writer
    final Instant t1 = Instant.parse("2026-06-14T09:30:00Z");
    final Instant t2 = Instant.parse("2026-06-14T09:31:00Z");

    final Intervention first =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t1,
            "first fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final Intervention second =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            t2,
            "second fix",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final InterventionLedgerWriter writer = new PulumiInterventionLedgerWriter(backendDir);
    writer.append(canonical(first));
    writer.append(canonical(second));

    // Read back via InterventionLedgerSource
    final InterventionLedgerSource source = new InterventionLedgerSource(backendDir);
    final InterventionLedger ledger = source.load();

    final List<Intervention> interventions = ledger.interventions();
    assertEquals(2, interventions.size(), "should recover both appended interventions");

    // Verify time-ordered (first then second)
    assertEquals(t1, interventions.get(0).when(), "first intervention by time");
    assertEquals("first fix", interventions.get(0).what());
    assertEquals(Provenance.OPERATOR_MANUAL, interventions.get(0).provenance());
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        interventions.get(0).problem());

    assertEquals(t2, interventions.get(1).when(), "second intervention by time");
    assertEquals("second fix", interventions.get(1).what());
    assertEquals(Provenance.OPERATOR_MANUAL, interventions.get(1).provenance());
    assertEquals(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        interventions.get(1).problem());
  }

  /**
   * Wrap an intervention into the canonical {@code intervention} Document the write seam carries.
   */
  private static Document canonical(Intervention intervention) {
    try {
      return new Document(
          Domain.DOCTOR.slug(),
          Coordinate.INTERVENTION.slug(),
          new ObjectMapper().writeValueAsString(intervention.toOutputMap()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
