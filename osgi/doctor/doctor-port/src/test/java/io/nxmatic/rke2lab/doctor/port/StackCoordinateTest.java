package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class StackCoordinateTest {

  @Test
  void creates_stack_coordinate_with_valid_project_and_stack() {
    final StackCoordinate coordinate =
        StackCoordinate.builder().project("intervention-ledger").stack("dev").build();

    assertEquals("intervention-ledger", coordinate.project());
    assertEquals("dev", coordinate.stack());
  }

  @Test
  void builder_sets_fields_to_correct_names_preventing_swaps() {
    final StackCoordinate coordinate = StackCoordinate.builder().project("P").stack("S").build();

    assertEquals("P", coordinate.project());
    assertEquals("S", coordinate.stack());
  }

  @Test
  void trims_whitespace_from_project_and_stack() {
    final StackCoordinate coordinate =
        StackCoordinate.builder().project("  intervention-ledger  ").stack("  dev  ").build();

    assertEquals("intervention-ledger", coordinate.project());
    assertEquals("dev", coordinate.stack());
  }

  @Test
  void rejects_null_project() {
    assertThrows(
        IllegalArgumentException.class,
        () -> StackCoordinate.builder().project(null).stack("dev").build());
  }

  @Test
  void rejects_blank_project() {
    assertThrows(
        IllegalArgumentException.class,
        () -> StackCoordinate.builder().project("   ").stack("dev").build());
  }

  @Test
  void rejects_null_stack() {
    assertThrows(
        IllegalArgumentException.class,
        () -> StackCoordinate.builder().project("intervention-ledger").stack(null).build());
  }

  @Test
  void rejects_blank_stack() {
    assertThrows(
        IllegalArgumentException.class,
        () -> StackCoordinate.builder().project("intervention-ledger").stack("   ").build());
  }
}
