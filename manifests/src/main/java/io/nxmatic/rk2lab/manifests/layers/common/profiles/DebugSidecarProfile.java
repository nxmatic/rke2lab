// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DebugSidecarProfile {

        private final boolean enabled;
    private final String annotationKey;
    private final String defaultEnabled;
    private final String portEnvName;
    private final String portValue;

    public DebugSidecarProfile(
                        final boolean enabled,
            final String annotationKey,
            final String defaultEnabled,
            final String portEnvName,
            final String portValue
    ) {
                this.enabled = enabled;
        this.annotationKey = annotationKey;
        this.defaultEnabled = defaultEnabled;
        this.portEnvName = portEnvName;
        this.portValue = portValue;
    }

    public Map<String, String> workloadAnnotations(final Map<String, String> extra) {
                if (!enabled) {
                        return Map.copyOf(extra);
                }
        LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
        annotations.put(annotationKey, defaultEnabled);
        annotations.putAll(extra);
        return Map.copyOf(annotations);
    }

        public Optional<LinkedHashMap<String, Object>> delveSidecar(
            final String name,
            final String image,
            final String scriptPath,
            final String scriptVolumeName
    ) {
                if (!enabled) {
                        return Optional.empty();
                }
        LinkedHashMap<String, Object> container = new LinkedHashMap<>();
        container.put("name", name);
        container.put("image", image);
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("command", List.of(scriptPath));
        container.put("env", List.of(
                Map.of(
                        "name", "KDNS_DEBUG_ENABLED",
                        "valueFrom", Map.of(
                                "fieldRef", Map.of("fieldPath", "metadata.annotations['" + annotationKey + "']")
                        )
                ),
                Map.of("name", portEnvName, "value", portValue)
        ));
        container.put("ports", List.of(Map.of(
                "containerPort", Integer.parseInt(portValue),
                "name", "dlv",
                "protocol", "TCP"
        )));
        container.put("resources", Map.of(
                "limits", Map.of("cpu", "50m", "ephemeral-storage", "128Mi", "memory", "128Mi"),
                "requests", Map.of("cpu", "10m", "ephemeral-storage", "64Mi", "memory", "64Mi")
        ));
        container.put("securityContext", Map.of(
                "allowPrivilegeEscalation", false,
                "capabilities", Map.of(
                        "add", List.of("SYS_PTRACE"),
                        "drop", List.of("ALL")
                ),
                "readOnlyRootFilesystem", false,
                "runAsNonRoot", false,
                "runAsUser", 0
        ));
        container.put("volumeMounts", List.of(
                Map.of("mountPath", "/scripts", "name", scriptVolumeName, "readOnly", true)
        ));
        return Optional.of(container);
    }
}
