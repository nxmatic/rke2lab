package io.nxmatic.rke2lab.manifests;

import java.util.*;
import org.cdk8s.*;
import org.junit.jupiter.api.Test;
import software.constructs.*;

/** Exploration: peut-on résoudre des ApiObjects via l'arbre CDK8s au lieu d'un registry custom? */
class Cdk8sTreeExplorationTest {

  public static void main(String[] args) {
    new Cdk8sTreeExplorationTest().exploreApiObjectResolution();
  }

  @Test
  void exploreApiObjectResolution() {
    // Setup: créer un chart avec plusieurs ApiObjects
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-test").build());
    Chart chart = new Chart(app, "test");

    // Créer une namespace avec annotation custom
    ApiObject namespace =
        new ApiObject(
            chart,
            "namespace-rke2lab-system",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2lab-system")
                        .annotations(
                            Map.of(
                                "rke2lab.io/ref-id", "cluster/runtime-system-namespace",
                                "io.nxmatic.rke2lab/domain", "cluster"))
                        .build())
                .build());

    // Créer un ConfigMap qui référence la namespace
    ApiObject configMap =
        new ApiObject(
            chart,
            "configmap-flox-env",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-env")
                        .namespace("rke2lab-system")
                        .build())
                .build());

    System.out.println("\n=== CDK8s Tree Exploration ===\n");

    // Test 1: Trouver par ID de construct
    System.out.println("Test 1: findChild() by construct ID");
    try {
      IConstruct found = chart.getNode().findChild("namespace-rke2lab-system");
      System.out.println("  ✓ Found: " + found.getNode().getId());
      if (found instanceof ApiObject) {
        ApiObject obj = (ApiObject) found;
        System.out.println("    Kind: " + obj.getKind());
        System.out.println("    Name: " + obj.getName());
      }
    } catch (Exception e) {
      System.out.println("  ✗ Error: " + e.getMessage());
    }

    // Test 2: Parcourir tous les noeuds
    System.out.println("\nTest 2: findAll() and filter");
    List<IConstruct> all = chart.getNode().findAll();
    System.out.println("  Total nodes: " + all.size());

    int apiObjectCount = 0;
    for (IConstruct node : all) {
      if (node instanceof ApiObject) {
        apiObjectCount++;
        ApiObject obj = (ApiObject) node;
        System.out.println("  - " + obj.getKind() + "/" + obj.getName());
      }
    }
    System.out.println("  ApiObject count: " + apiObjectCount);

    // Test 3: Accéder aux métadonnées
    System.out.println("\nTest 3: Access metadata");
    try {
      ApiObjectMetadataDefinition metadata = namespace.getMetadata();
      System.out.println("  Metadata class: " + metadata.getClass().getName());
      System.out.println("  Name via metadata: " + metadata.getName());

      // Essayer d'accéder aux annotations - peut ne pas fonctionner avant synth
      try {
        Object jsonObj = metadata.toJson();
        System.out.println("  toJson() result type: " + jsonObj.getClass().getName());
        System.out.println("  toJson() value: " + jsonObj);
      } catch (Exception e2) {
        System.out.println("  ✗ Can't access via toJson(): " + e2.getMessage());
      }
    } catch (Exception e) {
      System.out.println("  ✗ Error: " + e.getMessage());
    }

    // Test 4: Trouver par kind/name
    System.out.println("\nTest 4: Find by kind + name");
    Optional<ApiObject> foundByKindName =
        chart.getNode().findAll().stream()
            .filter(c -> c instanceof ApiObject)
            .map(c -> (ApiObject) c)
            .filter(
                obj -> "Namespace".equals(obj.getKind()) && "rke2lab-system".equals(obj.getName()))
            .findFirst();

    if (foundByKindName.isPresent()) {
      System.out.println("  ✓ Found by kind+name: " + foundByKindName.get().getName());
    } else {
      System.out.println("  ✗ Not found by kind+name");
    }

    // Test 5: Dépendances CDK8s natives
    System.out.println("\nTest 5: CDK8s native dependencies");
    configMap.addDependency(namespace);
    System.out.println("  ✓ Added dependency: configMap -> namespace");
    System.out.println("  Note: CDK8s handles ordering automatically");

    System.out.println("\n=== Synthesis ===");
    app.synth();
    System.out.println("  ✓ Complete\n");
  }
}
