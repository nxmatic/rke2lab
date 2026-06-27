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
    payload.put(ExchangeCatalog.FIELD_ACTION, Action.STOP.slug());
    final Document doc =
        new Document(Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), payload);

    assertEquals(Domain.DOCTOR.slug(), doc.domain());
    assertEquals(Coordinate.READINESS_VERDICT.slug(), doc.coordinate());
    assertEquals(Action.STOP.slug(), doc.payload().get(ExchangeCatalog.FIELD_ACTION).asText());
  }

  @Test
  void catalogConstantsAreTheCanonicalStrings() {
    // The single source of truth for field keys (the schema) — call sites must reference these,
    // never literals. Closed value domains (domain, coordinate, action, symptom kind) are now
    // typed enums and tested in ExchangeVocabularyTest.
    assertEquals("scenarioId", ExchangeCatalog.FIELD_SCENARIO_ID);
    assertEquals("failed", ExchangeCatalog.FIELD_FAILED);
    assertEquals("override", ExchangeCatalog.FIELD_OVERRIDE);
    assertEquals("action", ExchangeCatalog.FIELD_ACTION);
    assertEquals("reason", ExchangeCatalog.FIELD_REASON);
  }

  @Test
  void consultationFieldsArePinned() {
    assertEquals("narration", ExchangeCatalog.FIELD_NARRATION);
    assertEquals("diagnosisAdoc", ExchangeCatalog.FIELD_DIAGNOSIS_ADOC);
    assertEquals("symptomKind", ExchangeCatalog.FIELD_SYMPTOM_KIND);
    assertEquals("summary", ExchangeCatalog.FIELD_SUMMARY);
    assertEquals("details", ExchangeCatalog.FIELD_DETAILS);
  }
}
