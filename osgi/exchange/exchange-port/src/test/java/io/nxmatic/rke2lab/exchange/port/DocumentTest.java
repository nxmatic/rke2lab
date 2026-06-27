package io.nxmatic.rke2lab.exchange.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class DocumentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void carriesDomainCoordinateAndStructuredPayload() {
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put(ExchangeCatalog.FIELD_ACTION, ExchangeCatalog.ACTION_STOP);
    final Document doc =
        new Document(ExchangeCatalog.DOMAIN_DOCTOR, ExchangeCatalog.READINESS_VERDICT, payload);

    assertEquals(ExchangeCatalog.DOMAIN_DOCTOR, doc.domain());
    assertEquals(ExchangeCatalog.READINESS_VERDICT, doc.coordinate());
    assertEquals(
        ExchangeCatalog.ACTION_STOP, doc.payload().get(ExchangeCatalog.FIELD_ACTION).asText());
  }

  @Test
  void catalogConstantsAreTheCanonicalStrings() {
    // The single source of truth — call sites must reference these, never literals. Every
    // constant's
    // canonical value is pinned so a copy-paste drift (e.g. FIELD_REASON = "action") cannot slip
    // in.
    assertEquals("doctor", ExchangeCatalog.DOMAIN_DOCTOR);
    assertEquals("readiness-checkpoint", ExchangeCatalog.READINESS_CHECKPOINT);
    assertEquals("readiness-verdict", ExchangeCatalog.READINESS_VERDICT);
    assertEquals("scenarioId", ExchangeCatalog.FIELD_SCENARIO_ID);
    assertEquals("failed", ExchangeCatalog.FIELD_FAILED);
    assertEquals("override", ExchangeCatalog.FIELD_OVERRIDE);
    assertEquals("action", ExchangeCatalog.FIELD_ACTION);
    assertEquals("reason", ExchangeCatalog.FIELD_REASON);
    assertEquals("stop", ExchangeCatalog.ACTION_STOP);
    assertEquals("continue-degraded", ExchangeCatalog.ACTION_CONTINUE_DEGRADED);
  }

  @Test
  void consultationCoordinateAndFieldsArePinned() {
    assertEquals("consultation", ExchangeCatalog.CONSULTATION);
    assertEquals("narration", ExchangeCatalog.FIELD_NARRATION);
    assertEquals("diagnosisAdoc", ExchangeCatalog.FIELD_DIAGNOSIS_ADOC);
    assertEquals("symptomKind", ExchangeCatalog.FIELD_SYMPTOM_KIND);
    assertEquals("summary", ExchangeCatalog.FIELD_SUMMARY);
    assertEquals("details", ExchangeCatalog.FIELD_DETAILS);
  }
}
