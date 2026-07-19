package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.osgi.service.log.LogLevel;

/**
 * Raises the embedded Felix framework's own internal log verbosity ({@code felix.log.level}) for
 * the annotated test class. {@link OutOfContainerFrameworkExtension} reads it in {@code beforeAll}
 * and feeds the level into the framework config via {@link
 * io.nxmatic.rke2lab.osgi.runtime.framework.LaunchConfig#felixLevelOf(LogLevel)}.
 *
 * <p>This is the LAUNCHER's own log (resolver + bundle diagnostics, written to {@code System.out})
 * — distinct from the OSGi {@code LogService} and from application slf4j. It is the only place a
 * failed {@link OutOfContainerFrameworkExtension#resolve(java.util.List) resolve()} (which returns
 * a bare {@code false}) explains WHICH requirement could not be wired, so annotate the test you are
 * debugging:
 *
 * <pre>{@code
 * @OsgiWorld
 * @FrameworkLog(LogLevel.DEBUG)     // org.osgi.service.log.LogLevel — the one shared vocabulary
 * class SomeInContainerTest { … }
 * }</pre>
 *
 * <p>The level is the OSGi {@link LogLevel}: the SAME enum the operator's {@code logging:level}
 * knob speaks (see {@code BootstrapConfig#logLevel()}), so the framework's verbosity has one
 * vocabulary, whether a test or a Pulumi operator asks for it. Felix has no AUDIT/TRACE step —
 * {@code felixLevelOf} pins them to the nearest one.
 *
 * <p>It is an annotation, not a builder verb, on purpose: many tests obtain their extension from a
 * SHARED factory ({@code ScenarioTestkit.felix()}), where a per-test builder call is not reachable
 * — the annotation rides on the test class regardless of how the extension was assembled. A test
 * without it leaves the Felix default: quiet unless a test opts into the noise.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FrameworkLog {

  LogLevel value();
}
