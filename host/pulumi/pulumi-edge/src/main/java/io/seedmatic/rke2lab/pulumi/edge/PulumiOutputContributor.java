package io.seedmatic.rke2lab.pulumi.edge;

import java.util.Map;

/**
 * A source of Pulumi stack outputs. Each contributor stays structured internally and projects to
 * the flat {@code Map<String,Object>} Pulumi expects only here, in {@link #contribute()} — the
 * single flatten point. Contributors are collected by a {@link PulumiOutputRegistry}, which merges
 * every contribution into the final stack-output map and guarantees no two contributors clash on a
 * key.
 *
 * <p>The inversion this enables: the registry no longer needs to know each output by name (the old
 * omniscient hub). A new output is added by implementing this interface and registering it — never
 * by editing a central builder. Because {@code contribute()} is the only place a structured value
 * becomes a map, an internal consumer of that value reads its typed form, never a re-parsed map.
 */
public interface PulumiOutputContributor {

  /**
   * This contributor's identity, used only for diagnostics — the registry names it when reporting a
   * key collision or a null contribution. Not a key prefix; the keys are whatever {@link
   * #contribute()} returns.
   */
  String namespace();

  /**
   * The contributor's stack outputs, projected flat. Called exactly once by the registry at
   * assembly time. Must be non-null; may be empty. The returned keys are merged as-is into the
   * stack outputs, so they must not collide with another contributor's keys.
   */
  Map<String, Object> contribute();
}
