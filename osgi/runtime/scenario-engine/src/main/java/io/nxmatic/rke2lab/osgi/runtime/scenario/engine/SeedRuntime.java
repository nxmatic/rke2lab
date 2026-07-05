package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The STATIONARY discipline (spec §discipline-by-role, Figures 5 & 6): a runtime pipeline where the
 * world survives the run. Wearing it pulls {@link SeedRuntimeExtension} — the shared connect +
 * climb ONCE, then no per-method move: the domain bundles stay active across every pipeline, torn
 * down only at the root scope. The annotation the acceptance pipeline (and increment 2's seed
 * scenario) wear. Choosing the annotation IS choosing the role.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(SeedRuntimeExtension.class)
public @interface SeedRuntime {}
