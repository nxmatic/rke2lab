package io.seedmatic.rke2lab.manifests.contract;

import java.util.Map;

/**
 * How the manifests render resolves its effective facet against the branch HEAD, and the guard the
 * asking CLI verb carries. The AMONT twin of a verb intent: the sower (seed-master's grow or the
 * {@code manifests-cli}) fills it so the in-container synthesis knows whether the SEEDED facet is
 * authoritative, whether the recorded HEAD facet wins, or whether HEAD is OVERLAID with a sparse
 * set of operator overrides — without the host learning how the synthesis reads a branch.
 *
 * <ul>
 *   <li>{@link Verb#GROW} — the seeded facet is authoritative (the grow's Pulumi stack is the
 *       SSOT); no guard. The DEFAULT when unamended, so seed-master keeps applying its facet
 *       unchanged.
 *   <li>{@link Verb#INIT} — the seeded facet is authoritative, but the branch MUST be new (no
 *       recorded HEAD facet): guards against silently resetting an existing branch to the CLI args.
 *   <li>{@link Verb#UPDATE} — the recorded HEAD facet wins (the seeded publish/debug are ignored);
 *       the branch MUST exist. The steady-state re-render (the in-cluster render runs this).
 *   <li>{@link Verb#EDIT} — the recorded HEAD facet OVERLAID with {@link #overrides} (dotted JSON
 *       paths → boolean, only the toggles the operator set); the branch MUST exist. A deliberate
 *       facet change, re-rendered AND re-recorded in one shot.
 * </ul>
 *
 * <p>{@link #overrides} carries dotted JSON paths into the recorded facet (e.g. {@code
 * publish.mesh} → {@code true}, {@code debug.networking.enabled} → {@code false}) rather than a
 * nested facet, so the contract stays jackson-free: the CLI (which owns the arg → path mapping)
 * fills it and the synthesis applies each path generically onto the HEAD facet. Empty for every
 * verb but {@code EDIT}.
 */
public record RenderMode(Verb verb, Map<String, Boolean> overrides) {

  /** The render intent — which of seeded / HEAD / HEAD-overlaid facet the synthesis renders. */
  public enum Verb {
    GROW,
    INIT,
    UPDATE,
    EDIT
  }

  public RenderMode {
    verb = verb == null ? Verb.GROW : verb;
    overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
  }

  public static RenderMode grow() {
    return new RenderMode(Verb.GROW, Map.of());
  }

  public static RenderMode init() {
    return new RenderMode(Verb.INIT, Map.of());
  }

  public static RenderMode update() {
    return new RenderMode(Verb.UPDATE, Map.of());
  }

  public static RenderMode edit(Map<String, Boolean> overrides) {
    return new RenderMode(Verb.EDIT, overrides);
  }
}
