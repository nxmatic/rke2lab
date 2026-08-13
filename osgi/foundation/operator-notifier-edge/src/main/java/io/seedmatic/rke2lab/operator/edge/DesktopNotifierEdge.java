package io.seedmatic.rke2lab.operator.edge;

import io.seedmatic.rke2lab.operator.OperatorNotifier;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised operator-notification edge: satisfies {@link OperatorNotifier} over the JDK {@code
 * java.desktop} module — {@code Desktop.browse} pops the operator's browser, {@code SystemTray}
 * shows a banner. No shell-out, no OS-specific command; {@code java.awt.*} / {@code java.net.*} are
 * {@code java.*} packages, boot-delegated to every bundle, so the framework needs no {@code
 * system.packages.extra} entry.
 *
 * <p>Best-effort by contract: every path degrades to a logback WARN carrying the same URL / message
 * — a headless JVM (CI, a remote build) has no GUI session, so the operator reads the durable log
 * line and acts by hand. A notification never throws.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating} — like {@code CliAuthTokenContact}, it is a live
 * contact ({@code @OsgiService(await = false)} at the consumer), so under a survey/preview frontier
 * its filter matches neither this nor an absent one: a preview run resolves it empty and the
 * consuming scenario PENDS without opening a browser.
 */
@Component(service = OperatorNotifier.class, property = "rke2lab.gardening=cultivating")
public final class DesktopNotifierEdge implements OperatorNotifier {

  private static final Logger LOG = LoggerFactory.getLogger(DesktopNotifierEdge.class);

  @Override
  public void browse(URI url) {
    if (!openInBrowser(url)) {
      LOG.warn("Open this URL in your browser to continue: {}", url);
    }
  }

  @Override
  public void notify(String message) {
    LOG.warn("{}", message);
    showBanner(message);
  }

  private static boolean openInBrowser(URI url) {
    if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
      return false;
    }
    final Desktop desktop = Desktop.getDesktop();
    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
      return false;
    }
    try {
      desktop.browse(url);
      return true;
    } catch (IOException | UnsupportedOperationException ex) {
      LOG.debug("Desktop.browse({}) failed: {}", url, ex.getMessage());
      return false;
    }
  }

  private static void showBanner(String message) {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
      return;
    }
    try {
      final Image icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
      final TrayIcon trayIcon = new TrayIcon(icon, "rke2lab");
      trayIcon.setImageAutoSize(true);
      SystemTray.getSystemTray().add(trayIcon);
      trayIcon.displayMessage("rke2lab", message, TrayIcon.MessageType.INFO);
    } catch (AWTException ex) {
      LOG.debug("system-tray banner unavailable: {}", ex.getMessage());
    }
  }
}
