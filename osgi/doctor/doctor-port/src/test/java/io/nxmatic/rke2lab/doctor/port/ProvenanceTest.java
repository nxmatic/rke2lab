package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProvenanceTest {

  @Test
  void constants_expose_their_kebab_case_ids() {
    assertEquals("pulumi-engine", Provenance.PULUMI_ENGINE.id());
    assertEquals("operator-manual", Provenance.OPERATOR_MANUAL.id());
    assertEquals("external-change-detected", Provenance.EXTERNAL_CHANGE_DETECTED.id());
  }

  @Test
  void parse_matches_by_id_case_insensitively() {
    final Optional<Provenance> parsed = Provenance.parse("operator-manual");
    assertTrue(parsed.isPresent());
    assertEquals(Provenance.OPERATOR_MANUAL, parsed.get());
  }

  @Test
  void parse_matches_by_enum_name_case_insensitively() {
    final Optional<Provenance> parsed = Provenance.parse("OPERATOR_MANUAL");
    assertTrue(parsed.isPresent());
    assertEquals(Provenance.OPERATOR_MANUAL, parsed.get());
  }

  @Test
  void parse_of_blank_is_empty() {
    assertTrue(Provenance.parse("").isEmpty());
  }

  @Test
  void parse_of_null_is_empty() {
    assertTrue(Provenance.parse(null).isEmpty());
  }

  @Test
  void parse_of_unknown_is_empty() {
    assertTrue(Provenance.parse("nope").isEmpty());
  }
}
