package io.nxmatic.rke2lab.osgi.boot.logging;

import java.util.logging.Level;
import org.apache.felix.framework.Logger;

/**
 * Routes Felix's OWN internal log output ({@code felix.log.level} trace, the resolver diagnostics)
 * onto the JDK {@code java.util.logging} bus instead of {@code System.out}, so it joins the boot's
 * INVERTED logging flow:
 *
 * <pre>
 *   Felix internal Logger ────► JUL ─┐
 *   host slf4j (slf4j-jdk14) ──► JUL ─┼─► pax-logging-api JdkHandler ─► pax-logging-logback
 *   io.grpc / JDK / SeedLog ───► JUL ─┘
 * </pre>
 *
 * There is NO slf4j bridge on the JUL bus (a {@code SLF4JBridgeHandler} would loop against the host
 * {@code slf4j-jdk14} binding); pax's own {@code JdkHandler} drains JUL into its logback, the
 * single sink. Felix takes a Logger INSTANCE through the {@code felix.log.logger} framework
 * property; the default writes to {@code System.out}/{@code err} in {@code doLogOut}. Overriding
 * that one method is all it takes — level filtering, message assembly and {@code log(...)} fan-in
 * stay Felix's — so Felix's trace rides the JUL bus like every other host source instead of leaking
 * to the console.
 */
public final class FelixJulLogger extends Logger {

  private static final java.util.logging.Logger JUL =
      java.util.logging.Logger.getLogger("org.apache.felix.framework");

  @Override
  protected void doLogOut(int level, String message, Throwable throwable) {
    final Level julLevel =
        switch (level) {
          case LOG_ERROR -> Level.SEVERE;
          case LOG_WARNING -> Level.WARNING;
          case LOG_INFO -> Level.INFO;
          default -> Level.FINE; // LOG_DEBUG and anything finer
        };
    if (throwable != null) {
      JUL.log(julLevel, message, throwable);
    } else {
      JUL.log(julLevel, message);
    }
  }
}
