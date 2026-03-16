// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import org.yaml.snakeyaml.Yaml;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class LegacyPackageIncludeConstruct extends Construct {

    private final String manifestsSubpath = "rke2.d/bioskop/master/manifests.d";
    private final String legacyPathPrefix;

    protected LegacyPackageIncludeConstruct(
            final Construct scope,
            final String id,
            final String legacyPathPrefix
    ) {
        super(scope, id);
        this.legacyPathPrefix = legacyPathPrefix;

        try {
            includeLegacyPackageFiles();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to include legacy manifests for package prefix: " + legacyPathPrefix,
                    exception
            );
        }
    }

    private void includeLegacyPackageFiles() throws IOException {
        Path repoRoot = findRepoRoot(Paths.get("").toAbsolutePath().normalize())
                .orElseThrow(() -> new IllegalStateException("Unable to locate repository root containing rke2.d"));

        Path manifestsRoot = repoRoot.resolve(manifestsSubpath);
        Path packageRoot = manifestsRoot.resolve(legacyPathPrefix);
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalStateException("Expected package directory is missing: " + packageRoot);
        }

        List<Path> manifestFiles = collectManifestFiles(packageRoot);
        if (manifestFiles.isEmpty()) {
            throw new IllegalStateException("No .yml manifests found under: " + packageRoot);
        }

        int index = 0;
        for (Path manifestFile : manifestFiles) {
            index = loadYamlFileAsApiObjects(packageRoot, manifestFile, index);
        }
    }

    private int loadYamlFileAsApiObjects(final Path packageRoot, final Path manifestFile, int index) throws IOException {
        String yamlText = Files.readString(manifestFile);
        Yaml yaml = new Yaml();
        int docIndex = 0;
        for (Object rawDoc : yaml.loadAll(yamlText)) {
            if (rawDoc == null) {
                docIndex++;
                continue;
            }
            if (!(rawDoc instanceof Map<?, ?> rawMap)) {
                throw new IllegalStateException("Unexpected YAML document type in " + manifestFile + ": " + rawDoc.getClass().getName());
            }

            Map<String, Object> document = normalizeMap(rawMap);
            String apiVersion = stringValue(document.get("apiVersion"));
            String kind = stringValue(document.get("kind"));
            if (apiVersion == null || apiVersion.isBlank() || kind == null || kind.isBlank()) {
                throw new IllegalStateException("YAML document missing apiVersion/kind in " + manifestFile + " (doc index " + docIndex + ")");
            }

            Path relativePath = packageRoot.relativize(manifestFile);
            String objectId = objectId(index++, relativePath, docIndex++, kind, document.get("metadata"));

            ApiObject apiObject = new ApiObject(
                    this,
                    objectId,
                    ApiObjectProps.builder()
                            .apiVersion(apiVersion)
                            .kind(kind)
                            .build()
            );

            for (Map.Entry<String, Object> entry : document.entrySet()) {
                String key = entry.getKey();
                if ("apiVersion".equals(key) || "kind".equals(key)) {
                    continue;
                }
                apiObject.addJsonPatch(JsonPatch.add("/" + escapeJsonPointer(key), entry.getValue()));
            }
        }
        return index;
    }

    private List<Path> collectManifestFiles(final Path packageRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(packageRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> packageRoot.relativize(path).toString()))
                    .toList();
        }
    }

    private Optional<Path> findRepoRoot(final Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve("rke2.d"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private String objectId(
            final int index,
            final Path relativePath,
            final int docIndex,
            final String kind,
            final Object metadata
    ) {
        String normalizedPath = relativePath.toString().replace('\\', '/');
        String namePart = "";
        if (metadata instanceof Map<?, ?> metadataMap) {
            Object name = metadataMap.get("name");
            if (name != null) {
                namePart = "-" + name.toString();
            }
        }
        String normalized = kind + namePart + "-" + normalizedPath + "-" + docIndex;
        String sanitized = normalized.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return String.format("obj-%04d-%s", index, sanitized);
    }

    private String escapeJsonPointer(final String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private Map<String, Object> normalizeMap(final Map<?, ?> source) {
        LinkedHashMap<String, Object> target = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            target.put(key, normalizeValue(entry.getValue()));
        }
        return target;
    }

    private Object normalizeValue(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizeValue).toList();
        }
        return value;
    }

    private String stringValue(final Object value) {
        return value == null ? null : value.toString();
    }
}
