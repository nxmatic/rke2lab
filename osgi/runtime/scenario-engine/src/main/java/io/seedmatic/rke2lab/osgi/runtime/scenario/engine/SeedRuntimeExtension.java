package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

/**
 * The STATIONARY teardown strategy (spec Figure 5, bottom lane): the world survives the run. It
 * adds NOTHING to the shared {@link BaseWorldExtension} — no per-method callback — so the cursor
 * climbs once (base {@code beforeAll}) and is never lowered between methods; the domain bundles
 * stay active across every pipeline. The only teardown is the base's {@code afterAll}, which
 * releases the connection at the ROOT of the scope (and only if it owns the lifecycle). Pulled by
 * {@link SeedRuntime}.
 *
 * <p>A distinct type rather than the base directly, so the annotation names a strategy and a third
 * discipline is a third subclass — the extension point stays uniform.
 */
public final class SeedRuntimeExtension extends BaseWorldExtension {}
