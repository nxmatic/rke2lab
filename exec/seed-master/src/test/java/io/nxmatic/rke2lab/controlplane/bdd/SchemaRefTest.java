package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.SchemaRef;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaRefTest {

  @Test
  void of_exposes_the_declared_id() {
    final SchemaRef ref = SchemaRef.of("dbus-tcp/connection-refused/v1");
    assertEquals("dbus-tcp/connection-refused/v1", ref.id());
  }

  @Test
  void blank_id_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> SchemaRef.of("  "));
    assertThrows(IllegalArgumentException.class, () -> SchemaRef.of(null));
  }

  @Test
  void parse_round_trips_a_nonblank_id() {
    final Optional<SchemaRef> parsed = SchemaRef.parse("x/y/v1");
    assertTrue(parsed.isPresent());
    assertEquals("x/y/v1", parsed.get().id());
  }

  @Test
  void parse_of_blank_is_empty() {
    assertTrue(SchemaRef.parse("").isEmpty());
    assertTrue(SchemaRef.parse(null).isEmpty());
  }

  @Test
  void constructor_trims_whitespace() {
    final SchemaRef ref = SchemaRef.of("  x/y/v1  ");
    assertEquals("x/y/v1", ref.id());
  }

  @Test
  void parse_trims_whitespace() {
    final Optional<SchemaRef> parsed = SchemaRef.parse("  x/y/v1  ");
    assertTrue(parsed.isPresent());
    assertEquals("x/y/v1", parsed.get().id());
  }
}
