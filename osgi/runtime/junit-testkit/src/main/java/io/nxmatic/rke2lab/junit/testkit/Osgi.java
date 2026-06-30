package io.nxmatic.rke2lab.junit.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Composed tag for a PERMANENT OSGi test: exercises the OSGi space ({@code @Tag("osgi")}) but is a
 * lasting regression guard, not a throwaway spike — so it carries {@code osgi} WITHOUT {@code
 * spike}. The permanent twin of {@link OsgiSpike}; lives in the testkit so every OSGi test wears
 * the same tag rather than re-declaring a bare {@code @Tag} — see {@code
 * test-tag-taxonomy-by-zone}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("osgi")
public @interface Osgi {}
