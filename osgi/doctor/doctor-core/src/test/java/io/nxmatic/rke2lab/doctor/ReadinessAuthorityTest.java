package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.internal.DefaultReadinessAuthority;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.ReadinessAuthority;
import org.junit.jupiter.api.Test;

class ReadinessAuthorityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ReadinessAuthority authority = new DefaultReadinessAuthority();

  private static Document checkpoint(String scenarioId, boolean failed, String override) {
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put(ExchangeCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(ExchangeCatalog.FIELD_FAILED, failed);
    if (override != null) {
      payload.put(ExchangeCatalog.FIELD_OVERRIDE, override);
    }
    return new Document(
        ExchangeCatalog.DOMAIN_DOCTOR, ExchangeCatalog.READINESS_CHECKPOINT, payload);
  }

  private static String action(Document verdict) {
    return verdict.payload().get(ExchangeCatalog.FIELD_ACTION).asText();
  }

  @Test
  void intrinsicWarningContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, null));
    assertEquals(ExchangeCatalog.READINESS_VERDICT, verdict.coordinate());
    assertEquals(ExchangeCatalog.ACTION_CONTINUE_DEGRADED, action(verdict));
  }

  @Test
  void operatorCriticalOverrideStops() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "critical"));
    assertEquals(ExchangeCatalog.ACTION_STOP, action(verdict));
  }

  @Test
  void operatorWarningOverrideContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "warning"));
    assertEquals(ExchangeCatalog.ACTION_CONTINUE_DEGRADED, action(verdict));
  }
}
