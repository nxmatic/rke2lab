package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The ISOLATION discipline (spec §discipline-by-role, Figures 5 & 6): a real test where each case
 * starts clean. Wearing it pulls {@link IsolatedWorldExtension} — the shared connect + climb, then
 * a method-scope sawtooth (descend at {@code @AfterEach}, re-ascend at {@code @BeforeEach}) that
 * re-lights the domain bundles around every case. Choosing the annotation IS choosing the role; a
 * third discipline is a third annotation pulling its own strategy, no change to the base.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(IsolatedWorldExtension.class)
public @interface IsolatedWorld {}
