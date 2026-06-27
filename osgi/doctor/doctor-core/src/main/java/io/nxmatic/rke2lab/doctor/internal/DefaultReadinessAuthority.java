package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.records.Severity;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.ReadinessAuthority;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side readiness authority: it owns the severity vocabulary the host no longer holds.
 * Given a checkpoint Document (scenario id, failed, optional operator override), it resolves the
 * effective severity — the operator override if present, else the scenario's intrinsic severity —
 * and maps it to a provisioning verdict ({@code stop} iff CRITICAL, else {@code
 * continue-degraded}). Published as the {@link ReadinessAuthority} seam so the flat host reads only
 * the verdict's action field.
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

  @Override
  public Document assess(Document checkpoint) {
    final String scenarioId =
        checkpoint.payload().path(ExchangeCatalog.FIELD_SCENARIO_ID).asText("");
    final String override =
        checkpoint.payload().hasNonNull(ExchangeCatalog.FIELD_OVERRIDE)
            ? checkpoint.payload().get(ExchangeCatalog.FIELD_OVERRIDE).asText()
            : null;

    final Severity effective =
        override != null
            ? Severity.parse(override).orElseGet(() -> intrinsicFor(scenarioId))
            : intrinsicFor(scenarioId);

    final boolean stop = effective == Severity.CRITICAL;
    final ObjectNode verdict = mapper.createObjectNode();
    verdict.put(
        ExchangeCatalog.FIELD_ACTION,
        stop ? ExchangeCatalog.ACTION_STOP : ExchangeCatalog.ACTION_CONTINUE_DEGRADED);
    verdict.put(
        ExchangeCatalog.FIELD_REASON, scenarioId + " severity=" + effective.name().toLowerCase());
    return new Document(ExchangeCatalog.DOMAIN_DOCTOR, ExchangeCatalog.READINESS_VERDICT, verdict);
  }

  private Severity intrinsicFor(String scenarioId) {
    return INTRINSIC.getOrDefault(scenarioId, DEFAULT_INTRINSIC);
  }
}
