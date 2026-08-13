package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.auth.contract.AuthTokenContact;
import io.seedmatic.rke2lab.auth.contract.AuthTokenSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Upserts the github/flox launch tokens into the worktree's {@code .secrets}, preserving the file's
 * comments and layout. A beat of the incus PREPARE (§ provisioning-slice delta #10): a FILE
 * materialisation (regex YAML upsert, no Pulumi engine) over the SAME FS the scion computes its
 * {@code BootstrapPaths} on, run BEFORE the {@code worktree.dir} mount binds {@code .secrets} into
 * the instance. It was host-only in {@code main} only because it was nested in {@code
 * IncusResourceBootstrap} — an accident of place; in I6 every materialisation is a scion gesture.
 *
 * <p>Precedence per source: an environment variable wins, else the {@link AuthTokenContact} is
 * asked ({@code gh auth token} / {@code flox auth token}). A blank result leaves the file
 * untouched; the file is written only when the upsert changed it.
 */
public final class LaunchSecretsWriter {

  private static final Pattern GITHUB_HEADER = Pattern.compile("^([\\t ]*)github:\\s*(#.*)?$");
  private static final Pattern FLOX_HEADER = Pattern.compile("^([\\t ]*)flox:\\s*(#.*)?$");
  private static final Pattern USERNAME =
      Pattern.compile("^([\\t ]*username\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");
  private static final Pattern TOKEN =
      Pattern.compile("^([\\t ]*token\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");

  private final Optional<AuthTokenContact> tokens;
  private final UnaryOperator<@Nullable String> env;

  /**
   * A world booted without {@code auth-edge} publishes no {@link AuthTokenContact}; the writer
   * still resolves tokens from the environment (its higher-precedence source), so it takes the
   * contact as an {@link Optional} rather than requiring one.
   */
  public LaunchSecretsWriter(Optional<AuthTokenContact> tokens) {
    this(tokens, System::getenv);
  }

  /** Test seam: an injected environment accessor so token precedence is exercised hermetically. */
  LaunchSecretsWriter(Optional<AuthTokenContact> tokens, UnaryOperator<@Nullable String> env) {
    this.tokens = tokens;
    this.env = env;
  }

  /** Upsert both tokens into {@code secretsFile} — github first, then flox. */
  public void ensureTokensPresent(Path secretsFile) {
    ensureGithubToken(secretsFile);
    ensureFloxToken(secretsFile);
  }

  private void ensureGithubToken(Path secretsFile) {
    final String token =
        resolve(AuthTokenSource.GITHUB, env.apply("GITHUB_TOKEN"), env.apply("GH_TOKEN"));
    if (token.isBlank()) {
      return;
    }
    rewrite(secretsFile, original -> upsertGithub(original, token));
  }

  private void ensureFloxToken(Path secretsFile) {
    final String token =
        resolve(
            AuthTokenSource.FLOXHUB,
            env.apply("FLOXHUB_TOKEN"),
            env.apply("FLOX_TOKEN"),
            env.apply("FLOX_AUTH_TOKEN"));
    if (token.isBlank()) {
      return;
    }
    rewrite(secretsFile, original -> upsertFlox(original, token));
  }

  private String resolve(AuthTokenSource source, @Nullable String... envCandidates) {
    for (String candidate : envCandidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate.trim();
      }
    }
    return tokens.flatMap(contact -> contact.tokenFor(source)).orElse("");
  }

  private void rewrite(Path secretsFile, java.util.function.UnaryOperator<String> upsert) {
    try {
      final String original = Files.readString(secretsFile, StandardCharsets.UTF_8);
      final String updated = upsert.apply(original);
      if (!original.equals(updated)) {
        Files.writeString(secretsFile, updated, StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to update launch secrets: " + secretsFile, ex);
    }
  }

  private String upsertGithub(String content, String githubToken) {
    final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
    final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));
    final String usernameValue = yamlSingleQuoted("x-access-token");
    final String tokenValue = yamlSingleQuoted(githubToken);

    final int headerIndex = findHeader(lines, GITHUB_HEADER);
    if (headerIndex < 0) {
      appendBlock(lines, "github:", "  username: " + usernameValue, "  token: " + tokenValue);
      return String.join(lineSeparator, lines);
    }

    final String headerIndent = leadingWhitespace(lines.get(headerIndex));
    final String childIndent = headerIndent + "  ";
    final int blockStart = headerIndex + 1;
    final int blockEnd = blockEnd(lines, blockStart, indentationWidth(headerIndent));

    int usernameIndex = -1;
    int tokenIndex = -1;
    for (int i = blockStart; i < blockEnd; i++) {
      final Matcher usernameMatcher = USERNAME.matcher(lines.get(i));
      if (usernameMatcher.matches()) {
        lines.set(i, usernameMatcher.group(1) + usernameValue + nullToEmpty(usernameMatcher, 3));
        usernameIndex = i;
        continue;
      }
      final Matcher tokenMatcher = TOKEN.matcher(lines.get(i));
      if (tokenMatcher.matches()) {
        lines.set(i, tokenMatcher.group(1) + tokenValue + nullToEmpty(tokenMatcher, 3));
        tokenIndex = i;
      }
    }

    int insertIndex = blockEnd;
    if (usernameIndex < 0) {
      lines.add(insertIndex, childIndent + "username: " + usernameValue);
      insertIndex++;
      if (tokenIndex >= insertIndex) {
        tokenIndex++;
      }
    }
    if (tokenIndex < 0) {
      lines.add(insertIndex, childIndent + "token: " + tokenValue);
    }
    return String.join(lineSeparator, lines);
  }

  private String upsertFlox(String content, String floxToken) {
    final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
    final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));
    final String tokenValue = yamlSingleQuoted(floxToken);

    final int headerIndex = findHeader(lines, FLOX_HEADER);
    if (headerIndex < 0) {
      appendBlock(lines, "flox:", "  token: " + tokenValue);
      return String.join(lineSeparator, lines);
    }

    final String headerIndent = leadingWhitespace(lines.get(headerIndex));
    final String childIndent = headerIndent + "  ";
    final int blockStart = headerIndex + 1;
    final int blockEnd = blockEnd(lines, blockStart, indentationWidth(headerIndent));

    for (int i = blockStart; i < blockEnd; i++) {
      final Matcher tokenMatcher = TOKEN.matcher(lines.get(i));
      if (tokenMatcher.matches()) {
        lines.set(i, tokenMatcher.group(1) + tokenValue + nullToEmpty(tokenMatcher, 3));
        return String.join(lineSeparator, lines);
      }
    }
    lines.add(blockEnd, childIndent + "token: " + tokenValue);
    return String.join(lineSeparator, lines);
  }

  private int findHeader(List<String> lines, Pattern header) {
    for (int i = 0; i < lines.size(); i++) {
      if (header.matcher(lines.get(i)).matches()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The first line at or above the header's indentation (a comment/blank line stays in the block).
   */
  private int blockEnd(List<String> lines, int blockStart, int headerIndentWidth) {
    for (int i = blockStart; i < lines.size(); i++) {
      final String trimmed = lines.get(i).trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (indentationWidth(lines.get(i)) <= headerIndentWidth) {
        return i;
      }
    }
    return lines.size();
  }

  private void appendBlock(List<String> lines, String... block) {
    if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
      lines.add("");
    }
    lines.addAll(List.of(block));
  }

  private String nullToEmpty(Matcher matcher, int group) {
    return matcher.group(group) == null ? "" : matcher.group(group);
  }

  private String leadingWhitespace(String line) {
    return line.substring(0, indentationWidth(line));
  }

  private int indentationWidth(String line) {
    int width = 0;
    while (width < line.length()) {
      final char c = line.charAt(width);
      if (c != ' ' && c != '\t') {
        break;
      }
      width++;
    }
    return width;
  }

  private String yamlSingleQuoted(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
