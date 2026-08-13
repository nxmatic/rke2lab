package io.seedmatic.rke2lab.pulumi.edge;

/**
 * Builds a {@link StackHandleSnapshotSource} over a given {@link StackHandle}, for the out-world
 * adapter's read-contract tests. Naming the wiring here keeps the test's intent — "the source
 * reading from this stack handle" — readable, and is the only place tests construct the adapter.
 */
final class StackHandleSnapshotSourceFixture {

  private StackHandleSnapshotSourceFixture() {}

  static StackHandleSnapshotSource over(StackHandle handle) {
    return new StackHandleSnapshotSource(handle);
  }
}
