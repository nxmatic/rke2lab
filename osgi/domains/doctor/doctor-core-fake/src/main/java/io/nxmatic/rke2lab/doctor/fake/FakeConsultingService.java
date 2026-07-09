package io.nxmatic.rke2lab.doctor.fake;

import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.records.ReadinessCheckpoint;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * A fake {@link ConsultingService}, published by SCR with {@code variant=fake} so a test
 * connection's service selector resolves it instead of the live doctor — the two coexist in the
 * registry, told apart only by the property. It renders a minimal {@code consultation} SeedEnvelope
 * (a one-line narration echoing the checkpoint's scenario id) with no doctor graph, so a stage's
 * failure path can consult and log without the full HealthSystem. {@link #reviewDrift()} is a
 * no-op: there is no ledger to review offline.
 */
@Component(
    service = ConsultingService.class,
    property = {"variant=fake", "service.ranking:Integer=-1000"})
public final class FakeConsultingService implements ConsultingService {

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedEnvelope consult(SeedEnvelope checkpoint) {
    final ReadinessCheckpoint decoded = codec.decode(checkpoint, ReadinessCheckpoint.class);
    final Consultation consultation =
        new Consultation(
            decoded.scenarioId(),
            "fake consult: " + decoded.scenarioId() + " reviewed offline",
            "= Fake diagnosis\n\n" + decoded.scenarioId() + " consulted by the fake doctor.\n",
            Map.of(),
            List.of());
    return new SeedEnvelope(
        "doctor", DoctorCoordinate.CONSULTATION.slug(), codec.encode(consultation));
  }

  @Override
  public void reviewDrift() {
    // No ledger to review offline: the fake keeps no medical record.
  }
}
