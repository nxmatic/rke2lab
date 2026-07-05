package io.nxmatic.rke2lab.junit.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a {@code *Test} that EXERCISES a runtime BDD-as-engine pipeline ({@code @Tag("pipeline")}).
 * A single-axis wrapper, orthogonal to {@link OsgiWorld}: a pipeline-on-OSGi test stacks both
 * ({@code @OsgiWorld @Pipeline class …PipelineTest}).
 *
 * <p>Note the selection subtlety this exists to serve: surefire selects by class NAME (the {@code
 * *Test} patterns), so the pipeline ITSELF — a non-{@code *Test} class (e.g. {@code
 * BulletproofPipeline}) — is invisible to surefire and is played only by our {@code
 * JUnitLauncherCore}. This tag does NOT hide a pipeline; it marks the real {@code *Test} that
 * DRIVES one, so those can be found/filtered as a family. {@code pipeline} is a new tag, not
 * excluded by default, so pipeline tests run in the default loop.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("pipeline")
public @interface Pipeline {}
