package io.nxmatic.rke2lab.bbox.core;

/**
 * The flat verdict for one reconciled reservation — the home mirror of the library's reconcile
 * action, so no {@code io.nxmatic.bbox.reconcile.Action} crosses the seam. The bbox-edge maps the
 * library enum onto this one; the scion counts and projects over it.
 */
public enum BboxAction {
  CREATED,
  UPDATED,
  MATCHING,
  WOULD_CREATE,
  WOULD_UPDATE,
  EXTRA,
  IGNORED,
  FAILED
}
