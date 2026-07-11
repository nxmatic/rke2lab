package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.ClinicalReasoning;
import io.nxmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.diagnostic.ScrDiagnostics;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The load-bearing proof of the specialist-distribution increment, run IN-CONTAINER (where the
 * records natively live, so nothing crosses to the host JVM): the test INJECTS a tier-tagged {@link
 * io.nxmatic.rke2lab.doctor.spi.Specialist} + an empty {@link Cellar} into the registry, SCR
 * collects the specialist into {@code DefaultHealthSystem}'s tier-scoped {@code @Reference} roster,
 * the two Cellar-backed frontier registries activate (satisfying the institution's EHR + ledger
 * references), and an admitted doctor routes a consult to the contributed specialist.
 *
 * <p>This is the mechanism every domain (systemd, netplan, cluster) uses to contribute its own
 * diagnostician. The test registers its collaborators rather than relying on standing
 * {@code @Component} fakes — the same register mechanism, driven by the caller. It reads {@link
 * HealthSystem} from the registry through the bundle's own {@link BundleContext}: the doctor model
 * and its specialists never leave the OSGi world (the records-private invariant), so the proof must
 * run here, not from the bare JVM.
 */
class HealthSystemContributionTest {

  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");

  @Test
  void healthSystemCollectsTheContributedSpecialistAndAdmittedDoctorRoutesToIt() throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

    // The test INJECTS both collaborators — no standing fake. The test runs IN-CONTAINER (in
    // doctor-core's realm, this fragment shares its loader), so it can register a Specialist
    // directly, same as the Cellar; the register mechanism is uniform.
    //
    // Order matters: register the Specialist FIRST (tier-tagged, so DefaultHealthSystem's
    // MULTIPLE tier-scoped @Reference collects it into the roster on activation), THEN the Cellar
    // (the mandatory reference that unblocks activation of the two frontier registries →
    // MedicalRecordRegistry + InterventionLedgerRegistry → DefaultHealthSystem). Registering the
    // roster member before the trigger guarantees it is in the set when the institution activates.
    final Hashtable<String, Object> tierProps = new Hashtable<>();
    tierProps.put(ClinicianProperties.ROLE, ClinicianProperties.ROLE_DIAGNOSTICIAN);
    tierProps.put(ClinicianProperties.TIER, ClinicianProperties.TIER_DOMAIN);
    context.registerService(Specialist.class, new RoutingDiagnostician(), tierProps);
    context.registerService(Cellar.class, emptyCellar(), new Hashtable<>());

    // The institution activates only once SCR binds the tier-scoped roster (← the injected
    // RoutingDiagnostician) + the two Cellar-backed registries. A published HealthSystem service IS
    // that proof; a tracker awaits it deterministically (SCR activation is asynchronous after the
    // Cellar appears).
    final HealthSystem healthSystem = awaitHealthSystem(context);
    assertNotNull(
        healthSystem,
        "SCR must publish HealthSystem — DefaultHealthSystem activated with its tier-scoped roster +"
            + " the two Cellar-backed registries satisfied by the injected Cellar."
            // when this fails, the SCR report names which @Reference is still unbound (and on which
            // target filter), so the failure explains itself instead of just "service was null".
            + ScrDiagnostics.of(context).map(ScrDiagnostics::report).orElse(""));

    // Admit a patient and consult the symptom the injected diagnostician routes on. A prescription
    // proves the contributed specialist was bound into the tier-scoped roster AND routed to.
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
        "the consult must carry the contributed diagnostician's prescription — the tier-scoped"
            + " @Reference bound the injected Specialist into the roster and the Generalist routed"
            + " to it");
  }

  /**
   * Await the SCR-published {@link HealthSystem}, bounded — SCR activation is asynchronous after
   * the Cellar is registered. Uses only {@code org.osgi.framework} (resolvable in-container), not
   * {@code org.osgi.util.tracker} (a runtime-only package the fragment's host does not import).
   */
  private static HealthSystem awaitHealthSystem(BundleContext context) throws InterruptedException {
    final long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      final ServiceReference<HealthSystem> reference =
          context.getServiceReference(HealthSystem.class);
      if (reference != null) {
        final HealthSystem healthSystem = context.getService(reference);
        if (healthSystem != null) {
          return healthSystem;
        }
      }
      Thread.sleep(25);
    }
    return null;
  }

  /**
   * The diagnostician the test injects (tier-tagged at registration) to prove the roster collects a
   * domain-level Specialist and the admitted doctor routes to it. Routes on {@link
   * Specialty#SYSTEMD} (where {@code CONNECTION_REFUSED} routes) and always prescribes, so a
   * consult on that symptom yields a non-empty plan to assert on. A plain mock — the test registers
   * it, not a standing {@code @Component}.
   */
  private static final class RoutingDiagnostician implements Specialist {
    @Override
    public Specialty domain() {
      return Specialty.SYSTEMD;
    }

    @Override
    public Assessment assess(Referral referral) {
      return Assessment.of(
          SchemaRef.of("test/contribution-diagnostician/v1"),
          Map.of("symptom", referral.symptom().id()),
          "test diagnostician reasoning for " + referral.symptom().id());
    }

    @Override
    public Optional<Prescription> prescribe(Referral referral, Assessment assessment) {
      return Optional.of(
          Prescription.of(
              RemediationProgramRef.RESTART_UNIT,
              Map.of("symptom", referral.symptom().id()),
              "test prescription"));
    }
  }

  /**
   * An empty {@link Cellar} — the seam collaborator the test injects so the two frontier registries
   * activate. Yields no stored SeedEnvelopes, stores nowhere, has only itself as a neighbour: this
   * test proves the contribution + admission + routing, not the record/ledger fold.
   */
  private static Cellar emptyCellar() {
    return new Cellar() {
      @Override
      public void store(Parcel parcel, SeedEnvelope vegetal) {}

      @Override
      public List<SeedEnvelope> fetch(Parcel parcel) {
        return List.of();
      }

      @Override
      public List<Parcel> neighbours(Parcel parcel) {
        return List.of(parcel);
      }
    };
  }
}
