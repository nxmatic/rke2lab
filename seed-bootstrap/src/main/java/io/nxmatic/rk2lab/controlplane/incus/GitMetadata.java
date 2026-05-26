package io.nxmatic.rk2lab.controlplane.incus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Utilities for capturing git metadata at deployment time. */
final class GitMetadata {

  private GitMetadata() {}

  /**
   * Returns the current git branch name, or "unknown" if git is unavailable or repo is in detached
   * HEAD state.
   */
  static String currentBranch() {
    try {
      final ProcessBuilder pb = new ProcessBuilder("git", "branch", "--show-current");
      final Process process = pb.start();
      final String output = readOutput(process);
      process.waitFor();
      return output.isBlank() ? "detached-HEAD" : output.trim();
    } catch (IOException | InterruptedException ex) {
      return "unknown";
    }
  }

  /**
   * Returns the current git commit SHA (full 40-character hash), or "unknown" if git is
   * unavailable.
   */
  static String currentCommitSha() {
    try {
      final ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
      final Process process = pb.start();
      final String output = readOutput(process);
      process.waitFor();
      return output.isBlank() ? "unknown" : output.trim();
    } catch (IOException | InterruptedException ex) {
      return "unknown";
    }
  }

  private static String readOutput(Process process) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.readLine();
    }
  }
}
