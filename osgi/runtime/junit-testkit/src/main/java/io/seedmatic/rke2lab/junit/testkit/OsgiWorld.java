package io.seedmatic.rke2lab.junit.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * The OSGi-world marker: this test exercises the OSGi space ({@code @Tag("osgi")}). A single-axis
 * wrapper — it says WHICH world the test plays in, nothing else; orthogonal concerns (a spike, a
 * pipeline) stack their own tag at the use site rather than being folded into a composite. Lives in
 * the testkit so every OSGi test wears the same tag rather than re-declaring a bare {@code @Tag} —
 * see {@code test-tag-taxonomy-by-zone}.
 *
 * <p>The {@code @Tag("osgi")} STRING is unchanged (only the annotation type gained the {@code
 * World} suffix), so {@code -Dgroups=osgi} / {@code excludedGroups} selection is unaffected.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("osgi")
public @interface OsgiWorld {}
