package io.nxmatic.rk2lab.controlplane.incus.image;

import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import com.pulumi.incus.Image;
import com.pulumi.incus.ImageArgs;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.Provider;
import com.pulumi.incus.inputs.GetImagePlainArgs;
import com.pulumi.incus.inputs.ImageAliasArgs;
import com.pulumi.incus.inputs.ImageSourceFileArgs;
import com.pulumi.incus.outputs.GetImageResult;
import com.pulumi.deployment.InvokeOptions;
import com.pulumi.resources.CustomResourceOptions;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage A seed image orchestration for Incus image lifecycle.
 *
 * <p>This class is intentionally not a Pulumi dynamic resource provider. It coordinates local/remote
 * artifact materialization and then creates/reads a standard Incus provider-managed {@link Image}.
 */
public final class PulumiIncusImageProvider {

    private static final String REMOTE_BUILD_SCRIPT_RESOURCE = "incus/image/remote-build-incus-image.sh";
    private static final String INCUS_METADATA_FILENAME = "incus.tar.xz";
    private static final String INCUS_ROOTFS_FILENAME = "rootfs.squashfs";
    private static final int PREVIEW_ALIAS_PREFIX_MAX_LENGTH = 8;

    private final BootstrapConfig config;

    public PulumiIncusImageProvider(BootstrapConfig config) {
        this.config = config;
    }

    /**
     * Ensure the seed image exists and return its fingerprint output.
     */
    public Output<String> ensureSeedImageFingerprint(InvokeOptions invokeOptions, Provider provider) {
        final boolean preview = isDryRun();
        final Path workspace = resolvePreferredHostPath(config.worktree());
        final Path artifactDir = resolveArtifactDir(workspace);
        final Path distrobuilderConfigPath = materializeDistrobuilderConfig(workspace);
        final String expectedBuildChecksum = computeBuildChecksum(distrobuilderConfigPath);
        final Path checksumMarkerPath = artifactDir.resolve(".build-checksum.sha256");
        final Optional<String> existingFingerprint = resolveExistingSeedImageFingerprint(invokeOptions);
        if (existingFingerprint.isPresent() && checksumMarkerMatches(checksumMarkerPath, expectedBuildChecksum)) {
            return Output.of(existingFingerprint.get());
        }

        if (preview) {
            return Output.of(buildPreviewFingerprintSurrogate(expectedBuildChecksum));
        }

        final BuiltImageArtifacts artifacts = ensureLocalImageArtifacts();
        return createSeedImageFromArtifacts(provider, artifacts).fingerprint();
    }

    private boolean isDryRun() {
        return Deployment.getInstance().isDryRun();
    }

    private String buildPreviewFingerprintSurrogate(String expectedBuildChecksum) {
        final String aliasPrefix = shortAliasPrefix(config.imageAlias());
        return "preview-" + aliasPrefix + "-" + expectedBuildChecksum.substring(0, 16);
    }

    private String shortAliasPrefix(String alias) {
        final String normalized = alias == null
                ? ""
                : alias.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "image";
        }
        return normalized.length() <= PREVIEW_ALIAS_PREFIX_MAX_LENGTH
                ? normalized
                : normalized.substring(0, PREVIEW_ALIAS_PREFIX_MAX_LENGTH);
    }

    private Optional<String> resolveExistingSeedImageFingerprint(InvokeOptions invokeOptions) {
        try {
            final GetImageResult existingImage = IncusFunctions.getImagePlain(
                    GetImagePlainArgs.builder()
                            .name(config.imageAlias())
                            .project(config.incusProject())
                            .build(),
                    invokeOptions
            ).join();
            return Optional.ofNullable(existingImage.fingerprint());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Image createSeedImageFromArtifacts(Provider provider, BuiltImageArtifacts artifacts) {
        return new Image(
            "seed-image",
            ImageArgs.builder()
                .project(config.incusProject())
                .aliases(ImageAliasArgs.builder()
                    .name(config.imageAlias())
                    .build())
                .sourceFile(ImageSourceFileArgs.builder()
                    .metadataPath(artifacts.metadataPath().toString())
                    .dataPath(artifacts.dataPath().toString())
                    .build())
                .build(),
            CustomResourceOptions.builder()
                .provider(provider)
                .build()
        );
    }

    private BuiltImageArtifacts ensureLocalImageArtifacts() {
        final Path workspace = resolvePreferredHostPath(config.worktree());
        final Path artifactDir = resolveArtifactDir(workspace);
        final Path distrobuilderConfigPath = materializeDistrobuilderConfig(workspace);
        final String expectedBuildChecksum = computeBuildChecksum(distrobuilderConfigPath);
        final Path checksumMarkerPath = artifactDir.resolve(".build-checksum.sha256");

        Path readableArtifactDir = resolveReadableLocalArtifactDir(artifactDir);
        Path metadataPath = readableArtifactDir.resolve(INCUS_METADATA_FILENAME);
        Path dataPath = readableArtifactDir.resolve(INCUS_ROOTFS_FILENAME);

        if (Files.exists(metadataPath)
                && Files.exists(dataPath)
                && checksumMarkerMatches(checksumMarkerPath, expectedBuildChecksum)) {
            return new BuiltImageArtifacts(metadataPath, dataPath);
        }

        try {
            Files.createDirectories(artifactDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create image artifact directory: " + artifactDir, ex);
        }

        final String distrobuilderExecutable = tryResolveExecutable(config.imageBuilderBinary());
        if (!distrobuilderExecutable.isBlank()) {
            final List<String> buildCommand = buildIncusAsRootCommand(
                    distrobuilderExecutable,
                    distrobuilderConfigPath.toString(),
                    artifactDir.toString()
            );
            runCommandOrThrow(
                    workspace,
                    buildCommand,
                    "Failed to build Incus image artifacts using distrobuilder"
            );
        } else {
            runRemoteBuildOrThrow(workspace, distrobuilderConfigPath, artifactDir);
        }

        writeChecksumMarker(checksumMarkerPath, expectedBuildChecksum);

        metadataPath = artifactDir.resolve(INCUS_METADATA_FILENAME);
        dataPath = artifactDir.resolve(INCUS_ROOTFS_FILENAME);

        if (!Files.exists(metadataPath) || !Files.exists(dataPath)) {
            throw new IllegalStateException(
                    "Distrobuilder did not produce expected artifacts. Missing files in "
                            + artifactDir
                            + " (expected incus.tar.xz and rootfs.squashfs). "
                            + "Configured artifact directory: " + artifactDir
            );
        }


        return new BuiltImageArtifacts(metadataPath, dataPath);
    }

    private String computeBuildChecksum(Path distrobuilderConfigPath) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(distrobuilderConfigPath));

            digest.update((byte) '\n');
            digest.update(config.imageAlias().getBytes(StandardCharsets.UTF_8));

            digest.update((byte) '\n');
            final String builderBinary = config.imageBuilderBinary() == null ? "" : config.imageBuilderBinary();
            digest.update(builderBinary.getBytes(StandardCharsets.UTF_8));

            digest.update((byte) '\n');
            final String builderHost = config.imageBuilderHost() == null ? "" : config.imageBuilderHost();
            digest.update(builderHost.getBytes(StandardCharsets.UTF_8));

            digest.update((byte) '\n');
            digest.update(loadClasspathScript(REMOTE_BUILD_SCRIPT_RESOURCE).getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to compute distrobuilder checksum from config: " + distrobuilderConfigPath,
                    ex
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private boolean checksumMarkerMatches(Path checksumMarkerPath, String expectedChecksum) {
        if (!Files.exists(checksumMarkerPath)) {
            return false;
        }

        try {
            final String current = Files.readString(checksumMarkerPath, StandardCharsets.UTF_8).trim();
            return expectedChecksum.equals(current);
        } catch (AccessDeniedException ex) {
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    private void writeChecksumMarker(Path checksumMarkerPath, String checksum) {
        try {
            final Path parent = checksumMarkerPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    checksumMarkerPath,
                    checksum + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to write distrobuilder checksum marker: " + checksumMarkerPath,
                    ex
            );
        }
    }

    private Path resolveArtifactDir(Path workspace) {
        final Path configured = Path.of(config.imageSharedFolder());
        final Path sharedFolderPath = configured.isAbsolute()
                ? configured.normalize()
                : workspace.resolve(configured).normalize();
        return sharedFolderPath.resolve(config.imageAlias()).normalize();
    }

    private Path resolveSharedFolder(Path workspace) {
        final Path configured = Path.of(config.imageSharedFolder());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return workspace.resolve(configured).normalize();
    }

    private Path resolveReadableLocalArtifactDir(Path artifactDir) {
        final Path normalized = artifactDir.toAbsolutePath().normalize();
        final List<Path> candidates = new ArrayList<>();
        candidates.add(normalized);
        candidates.add(siblingNfsDir(normalized));

        final String raw = normalized.toString();
        if (raw.startsWith("/net/")) {
            final int privateIndex = raw.indexOf("/private/");
            if (privateIndex >= 0) {
                final String privatePath = raw.substring(privateIndex);
                final Path privateCandidate = Path.of(privatePath).normalize();
                candidates.add(privateCandidate);
                candidates.add(siblingNfsDir(privateCandidate));

                final String withoutPrivatePrefix = privatePath.substring("/private".length());
                final Path directCandidate = Path.of(withoutPrivatePrefix).normalize();
                candidates.add(directCandidate);
                candidates.add(siblingNfsDir(directCandidate));
            }
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve(INCUS_METADATA_FILENAME))
                    && Files.exists(candidate.resolve(INCUS_ROOTFS_FILENAME))) {
                return candidate;
            }
        }

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        return normalized;
    }

    private Path siblingNfsDir(Path path) {
        final Path fileName = path.getFileName();
        if (fileName == null) {
            return path;
        }
        return path.resolveSibling(fileName.toString() + ".nfs").normalize();
    }

    private Path materializeDistrobuilderConfig(Path workspace) {
        final String configuredPath = config.imageDistrobuilderConfig();
        if (configuredPath.startsWith("classpath:/")) {
            final String resourcePath = configuredPath.substring("classpath:/".length());
            final Path destination = workspace.resolve(".local.d/tmp/" + Path.of(resourcePath).getFileName());
            try {
                Files.createDirectories(destination.getParent());
                try (var in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("Classpath resource not found: " + resourcePath);
                    }
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return destination;
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Failed to materialize distrobuilder config from classpath: " + configuredPath,
                        ex
                );
            }
        }

        final Path configured = Path.of(configuredPath);
        final Path resolved = configured.isAbsolute() ? configured : workspace.resolve(configured);
        if (!Files.exists(resolved)) {
            throw new IllegalStateException("Distrobuilder config not found: " + resolved);
        }
        return resolved.normalize();
    }

    private String tryResolveExecutable(String configuredBinary) {
        if (configuredBinary == null || configuredBinary.isBlank()) {
            return "";
        }

        final String trimmedBinary = configuredBinary.trim();
        if (trimmedBinary.contains("/")) {
            final Path explicitPath = Path.of(trimmedBinary).toAbsolutePath().normalize();
            if (Files.isExecutable(explicitPath)) {
                return explicitPath.toString();
            }
            return "";
        }

        final Set<Path> searchDirectories = new LinkedHashSet<>();

        final String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String entry : pathEnv.split(":")) {
                if (entry != null && !entry.isBlank()) {
                    searchDirectories.add(Path.of(entry).toAbsolutePath().normalize());
                }
            }
        }

        final String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            searchDirectories.add(Path.of(javaHome).resolve("bin").toAbsolutePath().normalize());
        }

        searchDirectories.add(Path.of("/run/current-system/sw/bin"));
        searchDirectories.add(Path.of("/nix/var/nix/profiles/default/bin"));
        searchDirectories.add(Path.of("/opt/homebrew/bin"));
        searchDirectories.add(Path.of("/usr/local/bin"));
        searchDirectories.add(Path.of("/usr/bin"));
        searchDirectories.add(Path.of("/bin"));

        final String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            searchDirectories.add(Path.of(home).resolve(".flox/bin").toAbsolutePath().normalize());
        }

        for (Path directory : searchDirectories) {
            final Path candidate = directory.resolve(trimmedBinary).normalize();
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        return "";
    }

    private Path resolvePreferredHostPath(String path) {
        final Path primary = Path.of(path).toAbsolutePath().normalize();
        final List<Path> candidates = hostPathCandidates(primary);

        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Try next candidate.
            }
        }

        for (Path candidate : candidates) {
            if (!candidate.toString().startsWith("/net/")) {
                return candidate;
            }
        }

        return primary;
    }

    private List<Path> hostPathCandidates(Path primary) {
        final List<Path> candidates = new ArrayList<>();
        candidates.add(primary);

        final String normalized = primary.toString();
        if (normalized.startsWith("/private/")) {
            final Path directCandidate = Path.of(normalized.substring("/private".length())).normalize();
            candidates.add(directCandidate);
        }

        if (!normalized.startsWith("/net/")) {
            return deduplicated(candidates);
        }

        final int privateIndex = normalized.indexOf("/private/");
        if (privateIndex < 0) {
            return deduplicated(candidates);
        }

        final String privatePath = normalized.substring(privateIndex);
        final Path privateCandidate = Path.of(privatePath).normalize();
        candidates.add(privateCandidate);

        final String withoutPrivatePrefix = privatePath.substring("/private".length());
        final Path directCandidate = Path.of(withoutPrivatePrefix).normalize();
        candidates.add(directCandidate);

        return deduplicated(candidates);
    }

    private List<Path> deduplicated(List<Path> candidates) {
        final List<Path> unique = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!unique.contains(candidate)) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private List<String> buildIncusAsRootCommand(String distrobuilderExecutable,
                                                 String configPath,
                                                 String artifactDir) {
        final List<String> buildCommand = List.of(
                distrobuilderExecutable,
                "build-incus",
                configPath,
                artifactDir
        );

        if (isRunningAsRoot()) {
            return buildCommand;
        }

        final String sudoExecutable = tryResolveExecutable("sudo");
        if (sudoExecutable.isBlank()) {
            throw new IllegalStateException(
                    "distrobuilder build requires root privileges, but sudo was not found"
            );
        }

        return List.of(
                sudoExecutable,
                "-n",
                distrobuilderExecutable,
                "build-incus",
                configPath,
                artifactDir
        );
    }

    private boolean isRunningAsRoot() {
        return "root".equals(System.getProperty("user.name", ""));
    }

    private void runRemoteBuildOrThrow(Path workspace, Path distrobuilderConfigPath, Path artifactDir) {
        final String remoteHost = config.imageBuilderHost();
        if (remoteHost == null || remoteHost.isBlank()) {
            throw new IllegalStateException(
                    "Unable to locate local executable '" + config.imageBuilderBinary() + "' and no remote "
                            + "image.builderHost is configured"
            );
        }

        final String binary = config.imageBuilderBinary() == null || config.imageBuilderBinary().isBlank()
                ? "distrobuilder"
                : config.imageBuilderBinary().trim();
        final String remoteWorkspace = toRemotePath(workspace, remoteHost);
        final String remoteConfigPath = toRemotePath(distrobuilderConfigPath, remoteHost);
        final String remoteSharedFolder = toRemotePath(resolveSharedFolder(workspace), remoteHost);
        final String remoteArtifactDir = remoteSharedFolder + "/" + config.imageAlias();

        runRemoteScriptOverSshOrThrow(
                workspace,
                remoteHost,
                REMOTE_BUILD_SCRIPT_RESOURCE,
                List.of(remoteWorkspace, remoteConfigPath, remoteArtifactDir, binary),
                "Failed to build Incus image artifacts on remote builder host " + remoteHost
        );
    }

    private String toRemotePath(Path path, String remoteHost) {
        final String normalized = path.toAbsolutePath().normalize().toString();
        if (normalized.startsWith("/net/")) {
            return normalized;
        }

        final String netPrefix = "/net/" + remoteHostForNet(remoteHost) + ".local";
        if (normalized.startsWith("/private/")) {
            return netPrefix + normalized;
        }

        return netPrefix + "/private" + normalized;
    }

    private String remoteHostForNet(String remoteHost) {
        String host = remoteHost.trim();
        final int atIndex = host.lastIndexOf('@');
        if (atIndex >= 0 && atIndex < host.length() - 1) {
            host = host.substring(atIndex + 1);
        }

        final int colonIndex = host.indexOf(':');
        if (colonIndex > 0) {
            host = host.substring(0, colonIndex);
        }

        if (host.endsWith(".local")) {
            host = host.substring(0, host.length() - ".local".length());
        }

        if (host.isBlank()) {
            throw new IllegalStateException("Invalid remote host for image builder: " + remoteHost);
        }
        return host;
    }

    private void runRemoteScriptOverSshOrThrow(Path workingDirectory,
                                               String remoteHost,
                                               String scriptResourcePath,
                                               List<String> scriptArgs,
                                               String failureMessage) {
        final String script = loadClasspathScript(scriptResourcePath);

        final List<String> command = new ArrayList<>();
        command.add("ssh");
        command.add(remoteHost);
        command.add("sh");
        command.add("-lc");
        command.add("'set -eu; tmp_dir=$(mktemp -d); trap \"rm -rf \\\"$tmp_dir\\\"\" EXIT; "
            + "script_path=\"$tmp_dir/remote-build-incus-image.sh\"; "
            + "cat > \"$script_path\"; chmod 700 \"$script_path\"; "
            + "\"$script_path\" \"$@\"'");
        command.add("--");
        command.addAll(scriptArgs);

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        try {
            final Process process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(script.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        failureMessage + " (exit=" + exitCode + ")\nCommand: "
                                + String.join(" ", command) + "\nOutput:\n" + output
                );
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(failureMessage + ": " + ex.getMessage(), ex);
        }
    }

    private String loadClasspathScript(String scriptResourcePath) {
        try (var in = getClass().getClassLoader().getResourceAsStream(scriptResourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Classpath script not found: " + scriptResourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load classpath script: " + scriptResourcePath, ex);
        }
    }

    private void runCommandOrThrow(Path workingDirectory, List<String> command, String failureMessage) {
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        try {
            final Process process = pb.start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        failureMessage + " (exit=" + exitCode + ")\nCommand: "
                                + String.join(" ", command) + "\nOutput:\n" + output
                );
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(failureMessage + ": " + ex.getMessage(), ex);
        }
    }

    private record BuiltImageArtifacts(Path metadataPath, Path dataPath) {
    }
}
