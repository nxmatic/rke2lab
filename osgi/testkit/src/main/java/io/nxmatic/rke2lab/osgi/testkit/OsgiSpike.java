package io.nxmatic.rke2lab.osgi.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Composed tag for OSGi proofs: a throwaway spike ({@code @Tag("spike")}) that exercises the OSGi
 * space ({@code @Tag("osgi")}). Lives in the testkit so every OSGi proof wears the same tag pair
 * rather than re-declaring two {@code @Tag}s — see {@code test-tag-taxonomy-by-zone}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("osgi")
@Tag("spike")
public @interface OsgiSpike {}
