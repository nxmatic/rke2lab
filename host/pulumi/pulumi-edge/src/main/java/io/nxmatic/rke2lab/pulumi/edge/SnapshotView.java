package io.nxmatic.rke2lab.pulumi.edge;

import java.util.List;
import java.util.Map;

/**
 * The materialized outputs of a single snapshot, keyed by registration name. The pure replacement
 * for the host {@code StackSnapshot} at the boundary: the adapter eagerly copies ALL output keys
 * (never pre-filters), so the reader can query any registration name and flatten the list-of-lists
 * the backend produces for list-valued outputs.
 */
public record SnapshotView(Map<String, List<Object>> outputsByKey) {

  /** The outputs registered under {@code key}, or an empty list when none — never null. */
  public List<Object> outputsNamed(String key) {
    return outputsByKey.getOrDefault(key, List.of());
  }
}
