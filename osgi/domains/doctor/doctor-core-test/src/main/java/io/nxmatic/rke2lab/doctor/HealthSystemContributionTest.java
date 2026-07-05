package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.dsproof.FakeDiagnostician;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.ClinicalReasoning;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.diagnostic.ScrDiagnostics;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * The load-bearing proof of the specialist-distribution increment, run IN-CONTAINER (where the
 * records natively live, so nothing crosses to the host JVM): SCR collects the fragment-contributed
 * {@link io.nxmatic.rke2lab.doctor.spi.Specialist} {@code @Component} ({@link FakeDiagnostician},
 * tagged diagnostician/domain) into {@code DefaultHealthSystem}'s tier-scoped {@code @Reference}
 * roster, the institution activates (its EHR + ledger references satisfied by the {@code dsproof}
 * fake {@code @Component}s), and an admitted doctor routes a consult to the contributed specialist.
 *
 * <p>This is the mechanism every domain (systemd, netplan, cluster) will use to contribute its own
 * diagnostician — proven with a fake before the real specialists move home. It reads {@link
 * HealthSystem} from the registry through the bundle's own {@link BundleContext}: the doctor model
 * and its specialists never leave the OSGi world (the records-private invariant), so the proof must
 * run here, not from the bare JVM.
 */
class HealthSystemContributionTest {

  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");

  @Test
  void healthSystemCollectsTheContributedSpecialistAndAdmittedDoctorRoutesToIt() {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

    // The institution activated only because SCR bound the fragment-contributed @Components: the
    // tier-scoped roster ($000 ← FakeDiagnostician) + the fake EHR/ledger ports. A published
    // HealthSystem service IS that proof.
    final var reference = context.getServiceReference(HealthSystem.class);
    assertNotNull(
        reference,
        "SCR must publish HealthSystem — DefaultHealthSystem activated with its tier-scoped roster +"
            + " EHR + ledger references satisfied by the fragment-contributed @Components."
            // when this fails, the SCR report names which @Reference is still unbound (and on which
            // target filter), so the failure explains itself instead of just "service was null".
            + ScrDiagnostics.of(context).map(ScrDiagnostics::report).orElse(""));
    final HealthSystem healthSystem = context.getService(reference);
    assertNotNull(healthSystem, "the HealthSystem service reference must resolve to an instance");

    // Admit a patient and consult the symptom FakeDiagnostician routes on. A prescription proves
    // the
    // DS-contributed specialist was bound into the tier-scoped roster AND routed to by the doctor.
    final ConsultingService doctor = healthSystem.admit(DEV);
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", Map.of());
    final RemediationPlan plan =
        doctor
            .adapt(ClinicalReasoning.class)
            .orElseThrow()
            .consult(Symptom.CONNECTION_REFUSED, observation);
    assertTrue(
        plan.hasPrescriptions(),
        "the consult must carry the DS-contributed diagnostician's prescription — the tier-scoped"
            + " @Reference bound FakeDiagnostician into the roster and the Generalist routed to it");
  }
}
