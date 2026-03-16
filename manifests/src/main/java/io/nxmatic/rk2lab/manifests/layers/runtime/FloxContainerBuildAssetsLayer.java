// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class FloxContainerBuildAssetsLayer extends Construct {

    public static final String LEGACY_PATH_PREFIX = "runtime/flox-container-build-assets/";

    private static final String LAYER_NAME = "runtime";
    private static final String PACKAGE_NAME = "flox-container-build-assets";

    private final KptMetadata kptMetadata = new KptMetadata();

    public FloxContainerBuildAssetsLayer(final Construct scope, final String id) {
        super(scope, id);

        createBuildAssetsConfigMap();
    }

    private void createBuildAssetsConfigMap() {
        ApiObject configMap = new ApiObject(
                this,
                "configmap-flox-container-build-assets",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("ConfigMap")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-container-build-assets")
                                .namespace("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-runtime|flox-container-build-assets"
                                ))
                                .build())
                        .build()
        );

        configMap.addJsonPatch(JsonPatch.add("/data", Map.of(
                "rke2lab-flox-build.sh", readResource("/runtime/flox-container-build-assets/rke2lab-flox-build.sh"),
                "rke2lab-flox-build.yaml", readResource("/runtime/flox-container-build-assets/rke2lab-flox-build.yaml")
        )));
    }

    public static String readResource(final String resourcePath) {
        final InputStream input = FloxContainerBuildAssetsLayer.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Missing flox-container-build-assets resource: " + resourcePath);
        }

        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading flox-container-build-assets resource: " + resourcePath, ex);
        }
    }
}
