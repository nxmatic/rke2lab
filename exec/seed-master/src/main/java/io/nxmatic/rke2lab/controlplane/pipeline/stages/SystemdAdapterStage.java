package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bdd.ObservationView;
import io.nxmatic.rke2lab.controlplane.bdd.SimulatedSystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterScenario;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.pipeline.TopicFailure;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.SymptomKind;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Readiness gate, played as the BDD scenario it documents. The same Given/When/Then that runs
 * offline in tests runs here against the real probe; the scenario's captured snapshot becomes the
 * gate summary that flows into {@code SystemdAdapterResource}. During a Pulumi preview the gate
 * sets JGiven's dry-run property so the scenario renders its living-doc report without probing live
 * infrastructure — the same "walk structure, emit doc, no side effects" notion as {@code pulumi
 * preview} itself.
 */
public final class SystemdAdapterStage {

  private static final String JGIVEN_DRY_RUN = "jgiven.report.dry-run";

  /** Override key + report label for this gate. */
  private static final String SCENARIO_ID = Checkpoint.SYSTEMD_ADAPTER.slug();

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  @Nullable private final ReportModel runbook;
  @Nullable private final ConsultationLog consultations;
  @Nullable private final ConsultingService doctor;
  private final SystemdAdapterProbe liveProbe;
  private final Consumer<Map<String, Object>> sink;
  private final ReadinessAuthority readinessAuthority;

  /**
   * The run's stable instant (the deployment timestamp), written into the consult checkpoint so
   * OSGi stamps the expectations it derives — one source of truth shared with the egress, so the
   * Pulumi state shows no drift. Absent on the verdict-only test path (no consult runs there).
   */
  @Nullable private final Instant recordedAt;

  /**
   * The seam payload is a serialized JSON String; the host builds/reads it with its own jackson.
   */
  private final ObjectMapper mapper = new ObjectMapper();

  private final DocumentCodec codec = new DocumentCodec();

  public SystemdAdapterStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      @Nullable ReportModel runbook,
      @Nullable ConsultationLog consultations,
      @Nullable ConsultingService doctor,
      SystemdAdapterProbe liveProbe,
      Consumer<Map<String, Object>> sink,
      ReadinessAuthority readinessAuthority,
      Instant recordedAt) {
    this.config = config;
    this.policy = policy;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.doctor = doctor;
    this.liveProbe = liveProbe;
    this.sink = sink;
    this.readinessAuthority = readinessAuthority;
    this.recordedAt = recordedAt;
  }

  /**
   * The verdict-decision seam, without the optional reporting collaborators — package-private so it
   * stays off the live public API: the only caller is the same-package test fixture, which bridges
   * it to a public factory. A proof that exercises only the failing-probe → authority-verdict path
   * has no runbook, consultation log, doctor, or recordedAt to supply; routing through this
   * overload keeps that absence here rather than as nulls at the call site.
   */
  SystemdAdapterStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      SystemdAdapterProbe liveProbe,
      Consumer<Map<String, Object>> sink,
      ReadinessAuthority readinessAuthority) {
    this(
        config,
        policy,
        pulumiMode,
        readinessLogger,
        null,
        null,
        null,
        liveProbe,
        sink,
        readinessAuthority,
        null);
  }

  public SystemdAdapterStage launch() {
    final boolean preview = pulumiMode && Deployment.getInstance().isDryRun();

    // A fake incident is PREVIEW-ONLY: the simulate map is consulted only during `pulumi preview`,
    // never during a real `pulumi up`. Gating purely on dry-run is the safety contract — a stale
    // simulate entry can never inject a fake failure into real provisioning (which, at CRITICAL
    // severity, would abort an apply over a defect that does not exist). In preview, the simulated
    // scenario lifts dry-run and runs a canned failing probe (emitting the typed symptom) so the
    // incident renders without touching live infrastructure.
    // The policy carries raw config; the host parses the preview-simulate failure-kind into the
    // seam's SymptomKind here (the host owns the preview interpretation; no doctor type involved).
    final Optional<SymptomKind> simulated =
        preview
            ? policy.preview().rawSimulate(SCENARIO_ID).flatMap(SymptomKind::parse)
            : Optional.empty();

    // Normal preview skips step bodies (deferred-preview); a simulated preview runs them against
    // the fake probe so the failure is visible.
    final boolean dryRun = preview && simulated.isEmpty();

    // Capture the observation as the probe produces it, so it is available at the catch site for
    // the
    // doctor even when the Then assertion throws (the failed observation carries the typed
    // symptom).
    final ObservationView[] observationHolder = new ObservationView[1];
    // The injected live probe is the default; a preview-only simulated incident overrides it (and
    // only then). Live injects the real endpoint gate; tests inject a fake and play the same
    // launch(), so the scenario script lives in one place.
    final SystemdAdapterProbe underlying =
        simulated.<SystemdAdapterProbe>map(SimulatedSystemdAdapterProbe::of).orElse(liveProbe);
    final SystemdAdapterProbe probe =
        cfg -> {
          final ObservationView produced = underlying.probe(cfg);
          observationHolder[0] = produced;
          return produced;
        };
    if (simulated.isPresent()) {
      log("⚙ " + SCENARIO_ID + " simulating incident: " + simulated.get().slug());
    }

    // Standalone (non-JUnit) scenarios have no report model wired; finished() NPEs without one.
    // Record into the caller-owned runbook when present so this scenario joins the shared DAG;
    // otherwise a local model (inline log only). Held outside the try so the prose is logged even
    // when the probe fails the scenario.
    final ReportModel reportModel = runbook != null ? runbook : new ReportModel();

    final String previousDryRun = System.getProperty(JGIVEN_DRY_RUN);
    if (dryRun) {
      System.setProperty(JGIVEN_DRY_RUN, "true");
    }

    ObservationView captured = null;
    Throwable failure = null;
    try {
      final Scenario<
              SystemdAdapterScenario.Given,
              SystemdAdapterScenario.When,
              SystemdAdapterScenario.Then>
          scenario =
              Scenario.create(
                  SystemdAdapterScenario.Given.class,
                  SystemdAdapterScenario.When.class,
                  SystemdAdapterScenario.Then.class);
      scenario.setModel(reportModel);
      scenario.startScenario(Checkpoint.SYSTEMD_ADAPTER.scenarioTitle());
      try {
        scenario.given().the_seed_node(config.systemdAdapterDbusHost(), config).probed_by(probe);
        scenario.when().the_systemd_adapter_probe_runs();
        scenario.then().the_dbus_endpoint_responds();
      } finally {
        // finished() flushes the scenario (steps + status) into the model so the node RENDERS —
        // pass or fail. It must run even when a step threw (it re-throws that failure, caught
        // below); skipping it on failure leaves the failed node empty in the runbook.
        scenario.finished();
      }
      captured = scenario.then().capturedObservation();
    } catch (Throwable cause) {
      failure = cause;
      captured =
          observationHolder[0]; // the failed observation (with its symptom), if the probe ran
    } finally {
      if (previousDryRun == null) {
        System.clearProperty(JGIVEN_DRY_RUN);
      } else {
        System.setProperty(JGIVEN_DRY_RUN, previousDryRun);
      }
    }

    if (failure == null) {
      // Success — or dry-run, where step bodies are skipped so no observation is produced.
      final ObservationView observation =
          captured != null ? captured : SeedSystemdAdapterEndpointGate.deferredPreview(config);
      sink.accept(observation.toOutputMap());
      return this;
    }

    // Failure: the patient consults the doctor, then the host asks the OSGi authority for the
    // provisioning verdict — the authority owns the severity vocabulary, the host reads only the
    // action field. No Severity type on the host.
    consultDoctor(captured);

    final Document checkpoint = checkpointDocument(SCENARIO_ID);
    final Document verdict = readinessAuthority.assess(checkpoint);
    final Action action = codec.decode(verdict, ReadinessVerdict.class).action();
    if (action == Action.STOP) {
      log("✗ " + SCENARIO_ID + " FAILED, verdict=stop → stopping provisioning");
      throw new TopicFailure("systemd adapter", failure);
    }
    log("⚠ " + SCENARIO_ID + " FAILED, verdict=continue-degraded → continuing in DEGRADED mode");
    sink.accept(degradedObservation(failure).toOutputMap());
    return this;
  }

  /**
   * The patient consults the doctor on failure: the host serializes the captured observation into a
   * checkpoint {@link Document} at this boundary (the only place the host renders the flat wire
   * shape), the doctor reasons OSGi-side (routing, plan, narration, the rendered AsciiDoc), and the
   * host logs the returned narration and keeps the consultation Document in the shared log — no
   * doctor type held host-side. A symptomless or absent observation (failure before the probe ran)
   * has nothing to route, so the consultation is skipped; a null doctor (test fixture) also skips
   * it, as the verdict decision is independent of the consult.
   */
  private void consultDoctor(ObservationView observation) {
    if (doctor == null || observation == null || observation.symptom().isEmpty()) {
      return;
    }
    final Document consultation = doctor.consult(consultCheckpoint(observation));
    log("⚕ " + parse(consultation.payload()).path(WorldGatewayCatalog.FIELD_NARRATION).asText());
    if (consultations != null) {
      consultations.record(consultation);
    }
  }

  /**
   * The consult checkpoint Document, serialized at the consult boundary: the captured observation
   * (one, in the flat {@code ObservationView.toOutputMap} shape — the symptom slug travels in it)
   * plus the run's stable {@code recordedAt}, so OSGi reconstructs the observation and stamps the
   * expectations it derives. The only place the host renders the wire shape — everywhere else the
   * observation stays a typed {@link ObservationView}. A distinct concern from {@link
   * #checkpointDocument} (the verdict checkpoint the authority reads).
   */
  private Document consultCheckpoint(ObservationView observation) {
    final ObjectNode payload = mapper.createObjectNode();
    payload.put(WorldGatewayCatalog.FIELD_SCENARIO_ID, SCENARIO_ID);
    payload.put(WorldGatewayCatalog.FIELD_RECORDED_AT, recordedAt.toString());
    payload
        .putArray(WorldGatewayCatalog.FIELD_OBSERVATIONS)
        .add(mapper.valueToTree(observation.toOutputMap()));
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), serialize(payload));
  }

  private ObservationView degradedObservation(Throwable failure) {
    return ObservationView.of(
        "degraded",
        Optional.empty(),
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status=degraded ("
            + failure.getMessage()
            + ")",
        Map.of("source", "systemd-adapter-endpoint-gate", "probeMode", "systemd-adapter-runtime"));
  }

  /** The checkpoint outcome as a structured Document for the readiness authority. */
  private Document checkpointDocument(String scenarioId) {
    final ObjectNode payload = mapper.createObjectNode();
    payload.put(WorldGatewayCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(WorldGatewayCatalog.FIELD_FAILED, true);
    policy
        .readiness()
        .rawOverride(scenarioId)
        .ifPresent(value -> payload.put(WorldGatewayCatalog.FIELD_OVERRIDE, value));
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), serialize(payload));
  }

  /** Parse a verdict payload String with the host's own jackson (no JsonNode crosses the seam). */
  private JsonNode parse(String payload) {
    try {
      return mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("malformed verdict payload", e);
    }
  }

  /** Serialize a checkpoint payload tree to the String the seam carries. */
  private String serialize(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize checkpoint payload", e);
    }
  }

  private void log(String message) {
    if (readinessLogger != null) {
      readinessLogger.accept(message);
    }
  }
}
