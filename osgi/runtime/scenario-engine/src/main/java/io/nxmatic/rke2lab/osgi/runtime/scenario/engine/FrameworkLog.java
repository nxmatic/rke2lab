package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Raises the embedded Felix framework's own internal log verbosity ({@code felix.log.level}) for
 * the annotated test class. {@link OutOfContainerFrameworkExtension} reads it in {@code beforeAll}
 * and feeds the level into the framework config.
 *
 * <p>This is the LAUNCHER's own log (resolver + bundle diagnostics, written to {@code System.out})
 * — distinct from the OSGi {@code LogService} and from application slf4j. It is the only place a
 * failed {@link OutOfContainerFrameworkExtension#resolve(java.util.List) resolve()} (which returns
 * a bare {@code false}) explains WHICH requirement could not be wired, so annotate the test you are
 * debugging:
 *
 * <pre>{@code
 * @OsgiWorld
 * @FrameworkLog(DEBUG)            // static-import FrameworkLog.Level.DEBUG
 * class SomeInContainerTest { … }
 * }</pre>
 *
 * <p>It is an annotation, not a builder verb, on purpose: many tests obtain their extension from a
 * SHARED factory ({@code ScenarioTestkit.felix()}), where a per-test builder call is not reachable
 * — the annotation rides on the test class regardless of how the extension was assembled. Default
 * (unannotated) is {@link Level#ERROR}: quiet unless a test opts into the noise.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FrameworkLog {

  Level value();

  /**
   * Felix's four {@code felix.log.level} steps; the int is the value Felix expects in its config.
   */
  enum Level {
    ERROR(1),
    WARNING(2),
    INFO(3),
    DEBUG(4);

    private final int felixLevel;

    Level(int felixLevel) {
      this.felixLevel = felixLevel;
    }

    int felixLevel() {
      return felixLevel;
    }
  }
}
