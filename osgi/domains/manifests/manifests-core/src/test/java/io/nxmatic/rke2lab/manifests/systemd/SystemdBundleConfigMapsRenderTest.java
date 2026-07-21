package io.nxmatic.rke2lab.manifests.systemd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.cdk8s.Cdk8sApps;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the readable rendering of the systemd scripts ConfigMap: a tab-free script must serialise
 * as a YAML literal block ({@code name.sh: |}), not an escaped double-quoted scalar. SnakeYAML
 * abandons block style for ANY scalar containing a tab (or a trailing space), so this is the
 * regression guard for the shfmt indentation: the {@code .editorconfig} (spaces, never tabs) keeps
 * the bundled scripts tab-free, and the spotless-shfmt gate enforces it — if either regresses and
 * shfmt's default tabs creep back in, the ConfigMap silently falls back to an unreadable {@code
 * "…\n…"} blob and this test fails. (A script using a {@code <<-} heredoc legitimately keeps its
 * required leading tabs, so it stays double-quoted — that is correct, and not what this test
 * asserts on.)
 */
class SystemdBundleConfigMapsRenderTest {

  @Test
  void tabFreeScriptRendersAsLiteralBlock(@TempDir Path tmp) throws IOException {
    final App app = Cdk8sApps.create(AppProps.builder().outdir(tmp.toString()).build());
    final SystemdChart chart = new SystemdChart(app, "systemd");
    final Path systemdDir = tmp.resolve("systemd");

    SystemdBundleConfigMaps.synthesize(chart, systemdDir);

    final String scripts =
        Files.readString(systemdDir.resolve(SystemdBundleConfigMaps.SCRIPTS_DOTFILE));

    // rke2lab-env-load.sh is a core, heredoc-free loader — space-indented, so it must ride as a
    // literal block. Its presence as `name: |` proves the bundle serialises readably.
    assertTrue(
        scripts.contains("rke2lab-env-load.sh: |"),
        "a tab-free script must serialise as a YAML literal block, not an escaped scalar:\n"
            + scripts
                .lines()
                .filter(l -> l.contains("rke2lab-env-load.sh"))
                .findFirst()
                .orElse(""));
    assertFalse(
        scripts.contains("rke2lab-env-load.sh: \""),
        "the script must NOT fall back to a double-quoted escaped scalar (a tab crept back in)");
  }
}
