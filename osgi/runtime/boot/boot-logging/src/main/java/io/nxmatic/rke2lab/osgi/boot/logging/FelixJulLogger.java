package io.nxmatic.rke2lab.osgi.boot.logging;

import java.util.logging.Level;

/**
 * Routes Felix's OWN internal log output ({@code felix.log.level} trace, the resolver diagnostics)
 * into {@code java.util.logging} instead of {@code System.out}, so it joins the single host log
 * path {@link HostLoggingBridge} sets up:
 *
 * <pre>
 *   Felix internal Logger ─► JUL ─┐
 *   io.grpc / JDK / SeedLog ─► JUL ─┼─► SLF4JBridgeHandler ─► slf4j ─► logback
 * </pre>
 *
 * Felix takes a Logger INSTANCE through the {@code felix.log.logger} framework property; the
 * default writes to {@code System.out}/{@code err} in {@code doLogOut}. Overriding that one method
 * is all it takes — the level filtering, message assembly and {@code log(...)} fan-in stay Felix's.
 * With this in place there is NO {@code System.out} remainder: every framework + bundle + host
 * source converges on the one logback context (pax-logging carries the OSGi LogService side, this
 * carries Felix's own).
 */
public final class FelixJulLogger extends org.apache.felix.framework.Logger {

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
