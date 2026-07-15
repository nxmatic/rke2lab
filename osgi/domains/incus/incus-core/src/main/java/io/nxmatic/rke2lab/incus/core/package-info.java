/**
 * The incus domain's host-tree LOGIC — the tree the instance mounts is incus's (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § CORRECTION 2026-07-14). Pure Java
 * driven by the incus scenarios in-container: the slot rotation ({@link
 * io.nxmatic.rke2lab.incus.core.HostSlotSelector}), the cellar-timeline fold ({@link
 * io.nxmatic.rke2lab.incus.core.HostTreeHead}), the two deltas ({@link
 * io.nxmatic.rke2lab.incus.core.HostTreeDiffer} / {@link
 * io.nxmatic.rke2lab.incus.core.HostTreeDelta} / {@link
 * io.nxmatic.rke2lab.incus.core.HostTreeDeltaRenderer}) and the per-file checksum ({@link
 * io.nxmatic.rke2lab.incus.core.HostTreeChecksummer}). The wire-records these read/write live in
 * {@code incus-contract}; this module holds only the logic.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.incus.core;
