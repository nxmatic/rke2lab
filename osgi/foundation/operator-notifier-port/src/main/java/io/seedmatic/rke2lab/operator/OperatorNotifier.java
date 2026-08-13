package io.seedmatic.rke2lab.operator;

import java.net.URI;

/**
 * The operator-notification port: the one door toward the operator's DESKTOP — a contact distinct
 * from any domain's external system. A scenario running mid-{@code pulumi up} cannot reach the
 * operator through the console (the gRPC engine captures stdout) nor the runbook (rendered only at
 * the end), so an "act now" signal must be a desktop action. This port abstracts that action; the
 * {@code operator-notifier-edge} satisfies it over the JDK {@code java.desktop} module.
 *
 * <p>Cross-cutting, not domain-specific: any domain that must pause for a human gesture may signal
 * the operator through it. {@code ghapp} (the GitHub-App registration, which blocks on a browser
 * approval) is its first consumer.
 *
 * <p>Best-effort by contract: on a headless JVM (no GUI session) the edge falls back to a durable
 * logback WARN carrying the same URL / message — a notification never throws, it degrades.
 */
public interface OperatorNotifier {

  /**
   * Open the operator's default browser at {@code url} — the action-required signal (the window
   * appearing IS the notification). Falls back to a logback WARN carrying the URL when no desktop
   * browser is reachable.
   */
  void browse(URI url);

  /**
   * Show a best-effort desktop notification carrying {@code message} (a system-tray banner where
   * one exists), always also recorded to the logback WARN channel so the message survives a
   * headless run.
   */
  void notify(String message);
}
