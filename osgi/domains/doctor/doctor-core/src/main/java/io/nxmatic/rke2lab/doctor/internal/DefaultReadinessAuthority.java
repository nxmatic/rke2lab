package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.records.Severity;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side readiness authority: it owns the severity vocabulary the host no longer holds.
 * Given a checkpoint Document (scenario id, failed, optional operator override), it resolves the
 * effective severity — the operator override if present, else the scenario's intrinsic severity —
 * and maps it to a provisioning verdict ({@code stop} iff CRITICAL, else {@code
 * continue-degraded}). Published as the {@link ReadinessAuthority} seam so the flat host reads only
 * the verdict's action field.
 *
 * <p>The Document payload is a serialized JSON {@code String}; this authority parses and serializes
 * it with its OWN jackson (a doctor-core bundle dependency) — no jackson type crosses the seam.
 */
@Component(service = ReadinessAuthority.class)
public final class DefaultReadinessAuthority implements ReadinessAuthority {

  /**
   * Each checkpoint's intrinsic severity — the doctor's vocabulary. systemd-adapter: master can
   * provision without the dbus adapter (degraded), so a failure is a WARNING unless overridden.
   */
  private static final Map<String, Severity> INTRINSIC =
      Map.of("systemd-adapter", Severity.WARNING);

  private static final Severity DEFAULT_INTRINSIC = Severity.WARNING;

  private final ObjectMapper mapper = new ObjectMapper();
  private final DocumentCodec codec = new DocumentCodec();

  @Override
  public Document assess(Document checkpoint) {
    final JsonNode payload = parse(checkpoint.payload());
    final String scenarioId = payload.path(WorldGatewayCatalog.FIELD_SCENARIO_ID).asText("");
    final String override =
        payload.hasNonNull(WorldGatewayCatalog.FIELD_OVERRIDE)
            ? payload.get(WorldGatewayCatalog.FIELD_OVERRIDE).asText()
            : null;

    final Severity effective =
        override != null
            ? Severity.parse(override).orElseGet(() -> intrinsicFor(scenarioId))
            : intrinsicFor(scenarioId);

    final Action action = effective == Severity.CRITICAL ? Action.STOP : Action.CONTINUE_DEGRADED;
    final ReadinessVerdict verdict =
        new ReadinessVerdict(action, scenarioId + " severity=" + effective.name().toLowerCase());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), codec.encode(verdict));
  }

  private Severity intrinsicFor(String scenarioId) {
    return INTRINSIC.getOrDefault(scenarioId, DEFAULT_INTRINSIC);
  }

  private JsonNode parse(String payload) {
    try {
      return mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed checkpoint payload", e);
    }
  }
}
