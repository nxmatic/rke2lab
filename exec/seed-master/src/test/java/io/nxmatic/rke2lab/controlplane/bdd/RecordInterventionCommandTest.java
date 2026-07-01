package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.port.InterventionIntake;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordInterventionCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DocumentCodec CODEC = new DocumentCodec();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /**
   * A Document-capturing writer — the seam now persists a canonical {@code intervention} Document.
   */
  private static final class CapturingWriter implements InterventionLedgerWriter {
    final List<Document> appended = new ArrayList<>();

    @Override
    public void append(Document intervention) {
      appended.add(intervention);
    }
  }

  /**
   * A faithful stand-in for the OSGi {@link InterventionIntake} verb: it echoes the request fields
   * into a canonical {@code intervention} Document (provenance defaulting to operator-manual), or
   * an error verdict for the one unknown-problem case below — without pulling in any doctor type,
   * the same discipline the production CLI keeps.
   */
  private static InterventionIntake fakeIntake() {
    return rawFacts -> {
      final JsonNode req = read(rawFacts.payload());
      final String problem = req.path(WorldGatewayCatalog.FIELD_PROBLEM).asText();
      if (problem.startsWith("no-such")) {
        return new Document(
            Domain.DOCTOR.slug(),
            Coordinate.READINESS_VERDICT.slug(),
            CODEC.encode(
                new ReadinessVerdict(Action.STOP, "unknown problem reference: " + problem)));
      }
      final ObjectNode out = MAPPER.createObjectNode();
      out.put("provenance", textOr(req, WorldGatewayCatalog.FIELD_PROVENANCE, "operator-manual"));
      out.put("when", req.path(WorldGatewayCatalog.FIELD_WHEN).asText());
      out.put("what", req.path(WorldGatewayCatalog.FIELD_WHAT).asText());
      out.put("problem", problem);
      if (req.hasNonNull(WorldGatewayCatalog.FIELD_PRESCRIPTION_REF)) {
        out.put("prescriptionRef", req.get(WorldGatewayCatalog.FIELD_PRESCRIPTION_REF).asText());
      }
      return new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), write(out));
    };
  }

  @Test
  void recordsOperatorManualInterventionByDefault() {
    final CapturingWriter writer = new CapturingWriter();
    final Instant now = Instant.parse("2026-06-14T09:30:00Z");

    final Document recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem", "systemd-adapter/connection-refused", "--what", "nft delete ..."
            },
            now,
            fakeIntake(),
            writer);

    final Map<String, Object> payload = payloadOf(recorded);
    assertEquals("operator-manual", payload.get("provenance"));
    assertEquals(now.toString(), payload.get("when"));
    assertEquals("nft delete ...", payload.get("what"));
    assertEquals("systemd-adapter/connection-refused", payload.get("problem"));
    assertFalse(payload.containsKey("prescriptionRef"));
    assertEquals(List.of(recorded), writer.appended);
  }

  @Test
  void explicitWhenOverridesInjectedNow() {
    final CapturingWriter writer = new CapturingWriter();
    final Instant injectedNow = Instant.parse("2026-06-14T09:30:00Z");

    final Document recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem",
              "systemd-adapter/connection-refused",
              "--what",
              "nft delete ...",
              "--when",
              "2026-06-10T00:00:00Z"
            },
            injectedNow,
            fakeIntake(),
            writer);

    assertEquals("2026-06-10T00:00:00Z", payloadOf(recorded).get("when"));
  }

  @Test
  void parsesPrescriptionRefWhenPresent() {
    final CapturingWriter writer = new CapturingWriter();

    final Document recorded =
        RecordInterventionCommand.record(
            new String[] {
              "--problem",
              "systemd-adapter/connection-refused",
              "--what",
              "restarted the unit",
              "--prescription-ref",
              "restart-systemd-unit"
            },
            Instant.parse("2026-06-14T09:30:00Z"),
            fakeIntake(),
            writer);

    assertEquals("restart-systemd-unit", payloadOf(recorded).get("prescriptionRef"));
  }

  @Test
  void missingProblemIsRejected() {
    final CapturingWriter writer = new CapturingWriter();

    final IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecordInterventionCommand.record(
                    new String[] {"--what", "nft delete ..."},
                    Instant.parse("2026-06-14T09:30:00Z"),
                    fakeIntake(),
                    writer));

    assertTrue(
        ex.getMessage().toLowerCase(Locale.ROOT).contains("problem"),
        () -> "message should mention problem: " + ex.getMessage());
  }

  @Test
  void missingWhatIsRejected() {
    final CapturingWriter writer = new CapturingWriter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordInterventionCommand.record(
                new String[] {"--problem", "systemd-adapter/connection-refused"},
                Instant.parse("2026-06-14T09:30:00Z"),
                fakeIntake(),
                writer));
  }

  @Test
  void unknownProblemRefIsRejectedAsVerdict() {
    // A bad reference comes back as an error verdict Document from the OSGi verb, which the command
    // turns into the InterventionRejected exit path — not a thrown parse error host-side.
    final CapturingWriter writer = new CapturingWriter();

    final RecordInterventionCommand.InterventionRejected rejected =
        assertThrows(
            RecordInterventionCommand.InterventionRejected.class,
            () ->
                RecordInterventionCommand.record(
                    new String[] {
                      "--problem", "no-such-checkpoint/whatever", "--what", "something"
                    },
                    Instant.parse("2026-06-14T09:30:00Z"),
                    fakeIntake(),
                    writer));

    assertTrue(rejected.getMessage().contains("no-such-checkpoint"));
    assertTrue(writer.appended.isEmpty(), "a rejected intervention must not be appended");
  }

  @Test
  void malformedWhenIsRejectedAsUsageError() {
    // A bad --when must surface as IllegalArgumentException (the uniform usage-error contract main
    // catches), not a raw DateTimeParseException that escapes the catch.
    final CapturingWriter writer = new CapturingWriter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordInterventionCommand.record(
                new String[] {
                  "--problem", "systemd-adapter/connection-refused",
                  "--what", "nft delete ...",
                  "--when", "not-a-timestamp"
                },
                Instant.parse("2026-06-14T09:30:00Z"),
                fakeIntake(),
                writer));
  }

  private static Map<String, Object> payloadOf(Document document) {
    try {
      return MAPPER.readValue(document.payload(), MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String textOr(JsonNode node, String field, String fallback) {
    return node.hasNonNull(field) ? node.get(field).asText() : fallback;
  }
}
