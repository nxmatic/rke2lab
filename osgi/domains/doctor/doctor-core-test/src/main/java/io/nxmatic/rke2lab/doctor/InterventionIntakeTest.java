package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionIntake;
import io.nxmatic.rke2lab.junit.testkit.diagnostic.ScrDiagnostics;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.InterventionRequest;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * The in-container proof of the ingress canonicalization seam: SCR activates {@code
 * DefaultInterventionIntake} on its own — it declares NO references, the whole point of routing
 * canonicalization through a standalone {@code @Component} rather than {@code HealthSystem} (which
 * would demand the EHR + ledger the CLI has no reason to publish). A published {@link
 * InterventionIntake} service IS that proof. It runs here, not from the bare JVM, because the
 * canonicalizer builds the {@code Intervention} from the doctor vocabulary that never leaves the
 * OSGi world.
 */
class InterventionIntakeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DocumentCodec CODEC = new DocumentCodec();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  @Test
  void scrPublishesInterventionIntakeAndItCanonicalizesRawFacts() throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

    final var reference = context.getServiceReference(InterventionIntake.class);
    assertNotNull(
        reference,
        "SCR must publish InterventionIntake — DefaultInterventionIntake activates with NO"
            + " references, so no EHR/ledger need be published for canonicalization."
            + ScrDiagnostics.of(context).report());
    final InterventionIntake intake = context.getService(reference);
    assertNotNull(intake, "the InterventionIntake service reference must resolve to an instance");

    final InterventionRequest request =
        new InterventionRequest(
            "systemd-adapter/connection-refused",
            "nft delete ...",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-06-14T09:30:00Z"));
    final Document rawFacts =
        new Document(
            Domain.DOCTOR.slug(), Coordinate.INTERVENTION_REQUEST.slug(), CODEC.encode(request));

    final Document canonical = intake.canonicalize(rawFacts);
    assertEquals(
        Coordinate.INTERVENTION.slug(),
        canonical.coordinate(),
        "valid facts must canonicalize, not return an error verdict");
    final Map<String, Object> payload = MAPPER.readValue(canonical.payload(), MAP);
    assertEquals("operator-manual", payload.get("provenance"));
    assertEquals("systemd-adapter/connection-refused", payload.get("problem"));
    assertEquals("nft delete ...", payload.get("what"));
  }

  @Test
  void anUnparseableProblemComesBackAsAnErrorVerdict() throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
    final InterventionIntake intake =
        context.getService(context.getServiceReference(InterventionIntake.class));

    final InterventionRequest request =
        new InterventionRequest(
            "no-such-checkpoint/whatever",
            "something",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-06-14T09:30:00Z"));
    final Document rawFacts =
        new Document(
            Domain.DOCTOR.slug(), Coordinate.INTERVENTION_REQUEST.slug(), CODEC.encode(request));

    final Document verdict = intake.canonicalize(rawFacts);
    assertEquals(
        Coordinate.READINESS_VERDICT.slug(),
        verdict.coordinate(),
        "a bad reference must return an error verdict, not throw across the seam");
    final ReadinessVerdict decoded = CODEC.decode(verdict.payload(), ReadinessVerdict.class);
    assertTrue(
        decoded.reason().contains("no-such-checkpoint"),
        () -> "the verdict reason must name the bad reference: " + decoded);
  }
}
