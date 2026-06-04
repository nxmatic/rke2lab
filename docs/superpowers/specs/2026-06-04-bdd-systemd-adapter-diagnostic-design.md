# BDD Diagnostic Scenario: SystemD Adapter Reachability

**Date:** 2026-06-04
**Status:** Approved for implementation
**Author:** Claude (brainstorming session with user)

## Overview

Implement the first BDD diagnostic scenario to unblock master provisioning. The scenario verifies that the systemd adapter dbus-over-TCP endpoint becomes reachable during bootstrap, capturing targeted diagnostics on failure and publishing actor-specific reports as Pulumi stack resources.

**Current blocker:** Master provisioning fails at systemd adapter probe (port 12434 connection refused). This scenario will both document expected behavior AND provide diagnostic evidence to fix the issue.

## Goals

1. **Unblock provisioning:** Diagnose why port 12434 dbus probe fails
2. **Establish BDD pattern:** First implementation of BDD-as-readiness-gate architecture
3. **Living documentation:** Scenario becomes permanent resource provisioning gate, not a one-time debug tool
4. **Actor-specific outputs:** System gets metrics, orchestrator gets decisions, operator gets troubleshooting guide
5. **Robust provisioning:** Scenarios run as precondition checks between resources, blocking or allowing degraded mode based on severity

## Architecture

### BDD Components as Nested Classes

All BDD components nest inside `SeedSystemdAdapterEndpointGate`:

```
SeedSystemdAdapterEndpointGate.java (production class)
├── ensureReachable()                    (existing production method)
├── waitForRuntimeProbe()                (existing production method)
├── getSystemdUnitStatus()               (new: helper for diagnostics)
├── getJournalLogs()                     (new: helper for diagnostics)
│
├── interface DiagnosticCollector        (new: stage-specific typed interface)
│   ├── onDbusProbeStart(host, port)
│   ├── onDbusProbeSuccess(elapsed, status)
│   ├── onDbusProbeFailure(cause)
│   ├── requestGeneralistDiagnostic()    (returns: GeneralistDiagnostic)
│   └── publishDiagnostic(generalist, plan)
│
├── class GeneralistDiagnostic           (new: first-level triage)
│   ├── recordSymptom(name, details)
│   ├── invokeSpecialists()              (decides which specialists needed)
│   └── establishRemediationPlan()       (synthesizes findings → plan)
│
├── class DbusTcpSpecialist              (new: deep-dive on dbus-tcp)
│   ├── captureSystemdUnitStatus()
│   ├── captureJournalLogs()
│   ├── capturePortStatus()
│   └── suggestRemediation()
│
├── class NetworkSpecialist              (new: deep-dive on network)
│   ├── checkDnsResolution()
│   ├── checkRouting()
│   └── checkFirewall()
│
├── class IncusExecSpecialist            (new: deep-dive on incus-exec)
│   ├── checkInstanceState()
│   └── checkExecPath()
│
├── class SystemDiagnostics              (new: non-static inner class)
│   └── implements DiagnosticCollector   (captures metrics only)
│
├── class OrchestratorDiagnostics        (new: non-static inner class)
│   └── implements DiagnosticCollector   (captures decision data)
│
├── class OperatorDiagnostics            (new: non-static inner class)
│   └── implements DiagnosticCollector   (captures full diagnostics + remediation)
│
├── class Stages                         (new: non-static inner class)
│   ├── extends Stage<Stages>            (JGiven stage)
│   ├── systemd_adapter_probe_runs()     (DSL method)
│   └── dbus_endpoint_responds()         (DSL method)
│
├── class DiagnosticScenario             (new: nested class)
│   ├── extends ScenarioTest<...>        (JGiven scenario)
│   ├── systemd_adapter_becomes_reachable() (scenario method)
│   ├── severity()                       (returns: CRITICAL or WARNING)
│   └── execute()                        (programmatic invocation, returns ScenarioResult)
│
└── enum Severity                        (new: scenario severity levels)
    ├── CRITICAL                         (stop provisioning on failure)
    └── WARNING                          (continue in degraded mode on failure)
```

**Key decisions:**
- All BDD components are **nested** (impossible to forget)
- Actor implementations are **non-static** (can access production helper methods directly)
- **Uniform naming:** `DiagnosticCollector`, `SystemDiagnostics`, `OrchestratorDiagnostics`, `OperatorDiagnostics`, `Stages`, `DiagnosticScenario`, `Severity`
- **Doctor hierarchy:** Generalist always runs on failure → invokes Specialists → establishes RemediationPlan
- **Scenario owns severity:** Each scenario declares CRITICAL or WARNING based on domain knowledge
- **Operator policy override:** Strict mode can force all failures to CRITICAL during debugging

### Integration Point

**Scenarios as readiness gates** between resource provisioning steps:

```java
// In IncusResourceBootstrap.java or similar provisioning code

// 1. Provision Incus instance
final Instance masterInstance = new Instance("bioskop-master", instanceArgs);

// 2. Readiness gate: verify instance reachable
final ScenarioResult instanceReachable = InstanceReachabilityScenario.execute(masterInstance);
handleScenarioResult(instanceReachable);  // stop if CRITICAL failure

// 3. Provision systemd dbus-tcp unit (depends on instance)
final SystemdUnit dbusUnit = provisionDbusUnit(masterInstance);

// 4. Readiness gate: verify systemd adapter endpoint
final ScenarioResult adapterReachable = SystemdAdapterScenario.execute(dbusUnit);
handleScenarioResult(adapterReachable);  // stop if CRITICAL, continue degraded if WARNING

// 5. Provision manifest units (depend on systemd adapter)
final List<ManifestUnit> units = provisionManifestUnits(dbusUnit);
```

Scenarios run **programmatically** during provisioning, not as separate verification stage after bootstrap completes.

## Implementation Scope

### Phase 1: Foundation (this spec)

**Maven dependencies:**
- Add JGiven 1.3.1 to BOM (scope: `compile`, not `test`)
- Add JUnit Jupiter API to seed-master (scope: `compile`)
- **NO maven plugin needed** - scenarios run programmatically during pulumi up, not via `mvn test`

**New production helpers:**
- `SeedSystemdAdapterEndpointGate.getSystemdUnitStatus(String unitName)`
- `SeedSystemdAdapterEndpointGate.getJournalLogs(String unitName)`
- `SeedSystemdAdapterEndpointGate.checkPortListening(int port)`

**BDD nested components:**
- `DiagnosticCollector` interface (typed to dbus/systemd domain)
- `GeneralistDiagnostic` (first-level triage, decides specialist referrals)
- `DbusTcpSpecialist`, `NetworkSpecialist`, `IncusExecSpecialist` (deep-dive diagnostics)
- `SystemDiagnostics`, `OrchestratorDiagnostics`, `OperatorDiagnostics` (actor implementations)
- `Stages` (JGiven DSL vocabulary)
- `DiagnosticScenario` (scenario logic, severity declaration, programmatic execution)
- `Severity` enum (CRITICAL, WARNING)

**Provisioning integration:**
- Scenarios invoked directly in resource provisioning code (e.g., `IncusResourceBootstrap`)
- `handleScenarioResult()` method evaluates severity and operator policy
- Stop provisioning on CRITICAL failure, continue degraded on WARNING failure
- Publish reports on any failure (ConfigMap, stack outputs, filesystem)

**Shared report utilities:**
- `SharedMetricsCollector` (YAML output for system)
- `SharedDecisionLog` (JSON output for orchestrator)
- `SharedReportBuilder` (AsciiDoc output for operator)

### Phase 2: DSL Composition (future)

Extend DSL with stages from other production classes:
- `IncusResourceBootstrap.Stages` (incus_instance_exists, incus_instance_is_running)
- `SystemdTargetMonitor.Stages` (systemd_target_is_active, systemd_units_healthy)

Compose into richer scenarios:
```java
given().incus_instance_exists("bioskop-master")    // IncusResourceBootstrap.Stages
    .and().bootstrap_config_is_loaded();           // BootstrapConfig.Stages
when().systemd_adapter_probe_runs();               // SeedSystemdAdapterEndpointGate.Stages
then().dbus_endpoint_responds()                    // SeedSystemdAdapterEndpointGate.Stages
    .and().systemd_target_is_active("rke2lab.target"); // SystemdTargetMonitor.Stages
```

**Out of scope for Phase 1:** Focus on single-class scenario to establish pattern. DSL composition comes after pattern validation.

## Diagnostic Capture Strategy

**Principle:** Targeted capture - clean output on success, doctor hierarchy on failure.

### Doctor Hierarchy

On failure, always invoke the **Generalist** (first-level triage), which decides which **Specialists** to invoke:

```text
Scenario fails
  ↓
Generalist examines symptoms
  ├─→ symptom: connection_refused
  ├─→ symptom: port_unreachable
  └─→ symptom: timeout
  ↓
Generalist invokes Specialists
  ├─→ DbusTcpSpecialist (systemd status, journal, port check)
  ├─→ NetworkSpecialist (DNS, routing, firewall) [if needed]
  └─→ IncusExecSpecialist (instance state, exec path) [if needed]
  ↓
Generalist synthesizes findings
  ↓
Generalist establishes RemediationPlan
```

### Implementation

```java
public Stages systemd_adapter_probe_runs() {
  diagnostics.onDbusProbeStart(config.host(), config.port());

  try {
    final long start = System.nanoTime();
    final Map<String, Object> result = ensureReachable(config, null);
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    diagnostics.onDbusProbeSuccess(elapsed, result);  // Clean: just metrics

  } catch (Exception e) {
    diagnostics.onDbusProbeFailure(e);

    // ALWAYS invoke Generalist on failure
    final GeneralistDiagnostic generalist = diagnostics.requestGeneralistDiagnostic();
    generalist.recordSymptom("connection_refused", e);
    
    // Generalist decides which specialists to invoke
    generalist.invokeSpecialists();  // → DbusTcpSpecialist, NetworkSpecialist, etc.
    
    // Generalist synthesizes specialist findings and establishes plan
    final RemediationPlan plan = generalist.establishRemediationPlan();
    
    // Publish diagnostic + plan
    diagnostics.publishDiagnostic(generalist, plan);
  }

  return self();
}
```

### Specialist Implementation Example

```java
class DbusTcpSpecialist {
  List<Finding> diagnose(Symptom symptom) {
    final List<Finding> findings = new ArrayList<>();
    
    // Deep-dive: systemd unit status
    final String unitStatus = getSystemdUnitStatus("rke2lab-dbus-tcp-system-bus");
    findings.add(Finding.systemdUnit(unitStatus));
    
    // Deep-dive: journal logs (if unit failed)
    if (unitStatus.contains("inactive") || unitStatus.contains("failed")) {
      final List<String> logs = getJournalLogs("rke2lab-dbus-tcp-system-bus");
      findings.add(Finding.journalLogs(logs));
    }
    
    // Deep-dive: port status
    final boolean portListening = checkPortListening(12434);
    findings.add(Finding.portStatus(12434, portListening));
    
    return findings;
  }
  
  List<String> suggestRemediation(List<Finding> findings) {
    return List.of(
        "Check if socat is installed: incus exec master -- which socat",
        "Verify script exists: incus exec master -- ls -la /srv/host/systemd-scripts.d/",
        "Check systemd unit: incus exec master -- systemctl status rke2lab-dbus-tcp-system-bus.service",
        "View journal: incus exec master -- journalctl -u rke2lab-dbus-tcp-system-bus.service -n 50");
  }
}
```

## Severity and Operator Policy

### Scenario Owns Severity

Each scenario declares its severity based on domain knowledge:

```java
class DiagnosticScenario {
  public Severity severity() {
    // SystemD adapter failure is WARNING - master can provision without it (degraded mode)
    // Incus instance unreachable would be CRITICAL - nothing can proceed
    return Severity.WARNING;
  }
}
```

### Operator Policy Override

Operator can force strict mode during debugging:

```java
private void handleScenarioResult(ScenarioResult result) {
  final Severity effectiveSeverity = policy.isStrictMode() 
      ? Severity.CRITICAL  // Force fail-fast during debugging
      : result.severity(); // Respect scenario's domain knowledge
  
  if (result.failed() && effectiveSeverity == Severity.CRITICAL) {
    publishReports(result);
    throw new PipelineStageFailure("blocked by scenario: " + result.name());
  }
  
  if (result.failed() && effectiveSeverity == Severity.WARNING) {
    publishReports(result);
    markStackDegraded(result.name());
    log.warn("Continuing in degraded mode: {}", result.name());
    // Continue provisioning
  }
}
```

### Decision Matrix

| Scenario Severity | Operator Policy | Result Failed | Action |
| --- | --- | --- | --- |
| CRITICAL | any | yes | Stop provisioning, publish reports |
| CRITICAL | any | no | Continue |
| WARNING | strict | yes | Stop provisioning, publish reports |
| WARNING | lenient | yes | Continue degraded, publish reports |
| WARNING | any | no | Continue |

## Report Publication

### Multi-Channel Strategy

Same diagnostic data, three formats for three actors:

**A) ConfigMap (System - YAML metrics):**
```yaml
apiVersion: rke2lab.nxmatic.io/v1alpha1
kind: SystemDiagnostic
status: degraded
metrics:
  dbusProbeLatencyMs: -1
  dbusEndpointReachable: false
  requiredServicesActive:
    - rke2-server
    - containerd
failedServices:
  - rke2lab-dbus-tcp-system-bus
```

**B) Stack Output (Orchestrator - JSON decisions):**
```json
{
  "status": "degraded",
  "shouldRollback": false,
  "canProceedToNextStage": false,
  "scenariosPassed": 0,
  "scenariosTotal": 1,
  "degradedScenarios": ["systemd_adapter_becomes_reachable"],
  "blockingIssues": ["dbus_endpoint_unreachable"]
}
```

**C) Filesystem (Operator - AsciiDoc troubleshooting):**
```asciidoc
= Diagnostic Report: systemd-adapter
:toc: left

== Scenario: systemd_adapter_becomes_reachable

✓ Given incus instance exists
✗ When systemd adapter probe runs

Status: FAILED
Error: Connection refused at port 12434

=== Diagnostic: dbus-probe

==== systemd-units
```
rke2lab-dbus-tcp-system-bus.service: inactive (dead)
```

==== journal-logs
```
Jun 04 01:23:45 master systemd[1]: Starting rke2lab-dbus-tcp-system-bus.service...
Jun 04 01:23:45 master rke2lab-dbus-tcp-system-bus.sh: /usr/bin/socat: not found
Jun 04 01:23:45 master systemd[1]: rke2lab-dbus-tcp-system-bus.service: Main process exited, code=exited, status=127/n/a
Jun 04 01:23:45 master systemd[1]: rke2lab-dbus-tcp-system-bus.service: Failed with result 'exit-code'.
```

==== port-status
Port 12434: NOT LISTENING

=== Remediation

1. **Check if socat is installed:**
   ```bash
   incus exec master -- which socat
   ```

2. **Verify script exists and is executable:**
   ```bash
   incus exec master -- ls -la /srv/host/systemd-scripts.d/rke2lab-dbus-tcp-system-bus.sh
   ```

3. **Check systemd unit status:**
   ```bash
   incus exec master -- systemctl status rke2lab-dbus-tcp-system-bus.service
   ```

4. **View recent journal logs:**
   ```bash
   incus exec master -- journalctl -u rke2lab-dbus-tcp-system-bus.service -n 50
   ```

=== Analysis

The service failed because `socat` binary is not found. This usually means:
- The socat package is not installed in the Incus image
- The PATH in the systemd unit does not include socat's location
- The script is looking for socat in the wrong location

**Next steps:** Check the Incus image build definition to ensure socat is included in the package list.
```

### Implementation

```java
public VerificationStage publishReports() {
  // A) ConfigMap - YAML for system
  final String yamlReport = systemDiag.toYaml();
  new ConfigMap("diagnostic-reports", ConfigMapArgs.builder()
      .metadata(ObjectMetaArgs.builder()
          .name("rke2lab-diagnostic-reports")
          .namespace("rke2lab-system")
          .build())
      .data(Map.of("diagnostic-report.yaml", yamlReport))
      .build());

  // B) Stack outputs - JSON for orchestrator
  final Map<String, Object> jsonReport = orchDiag.toJson();
  ctx.export("diagnosticReports", Output.of(jsonReport));

  // C) File resources - AsciiDoc for operator
  final String adocReport = opDiag.toAsciiDoc();
  new local.Command("publish-diagnostic-reports-to-fs",
      local.CommandArgs.builder()
          .create(String.format(
              "mkdir -p /var/lib/rke2lab/reports && echo '%s' > /var/lib/rke2lab/reports/diagnostic-report.adoc",
              adocReport.replace("'", "'\\''")))
          .build());

  return this;
}
```

## Testing Strategy

**Phase 1:** Scenario runs as readiness gate during `pulumi up` resource provisioning.

**Expected outcome TODAY:**

- Incus instance "master" provisioned
- Systemd dbus-tcp unit provisioned
- **Readiness gate:** SystemdAdapterScenario.execute()
  - Probe fails (port 12434 connection refused)
  - Generalist invoked → DbusTcpSpecialist diagnoses (socat missing, unit failed)
  - RemediationPlan established
  - Reports published (ConfigMap, stack outputs, AsciiDoc)
  - Severity: WARNING → provisioning continues in degraded mode
- Manifest units provisioned (without systemd-adapter available)
- Stack marked as DEGRADED but READY

**After fix:**

- Same provisioning flow
- Readiness gate passes (port 12434 reachable)
- Clean report (checkmarks only)
- Stack marked as HEALTHY
- Scenario remains as permanent readiness gate

**This is the "doctor + gate" pattern:** block or allow based on severity, diagnose on failure, prevent regression forever.

## Dependencies

### Maven (BOM)
```xml
<properties>
  <jgiven.version>1.3.1</jgiven.version>
</properties>

<dependencyManagement>
  <dependency>
    <groupId>com.tngtech.jgiven</groupId>
    <artifactId>jgiven-junit5</artifactId>
    <version>${jgiven.version}</version>
  </dependency>
</dependencyManagement>
```

### Maven (seed-master)
```xml
<dependencies>
  <dependency>
    <groupId>com.tngtech.jgiven</groupId>
    <artifactId>jgiven-junit5</artifactId>
    <scope>compile</scope>  <!-- NOT test - scenarios are production code -->
  </dependency>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <scope>compile</scope>
  </dependency>
</dependencies>

<!-- NO maven plugin needed - scenarios run programmatically during pulumi up -->
```

## Success Criteria

### Immediate (Phase 1)

- [ ] JGiven dependencies added to BOM and seed-master (scope: compile)
- [ ] `SeedSystemdAdapterEndpointGate` has all nested BDD components (DiagnosticCollector, GeneralistDiagnostic, Specialists, actor implementations, Stages, DiagnosticScenario, Severity enum)
- [ ] Scenario runs as readiness gate during resource provisioning (not post-provisioning verification)
- [ ] Generalist diagnostic invokes appropriate specialists on failure
- [ ] Doctor hierarchy establishes remediation plan
- [ ] Severity declaration (WARNING for systemd-adapter) allows degraded mode
- [ ] Operator policy override (strict mode) forces fail-fast when needed
- [ ] AsciiDoc report captures actual port 12434 failure with full doctor hierarchy output
- [ ] ConfigMap, stack outputs, and filesystem reports all published on failure
- [ ] Provisioning continues in degraded mode (systemd-adapter WARNING + lenient policy)

### Future (Post-Fix)

- [ ] Fix actual socat/port 12434 issue based on diagnostic evidence
- [ ] Scenario turns green
- [ ] Clean report (checkmarks only) on successful deployment
- [ ] Scenario remains in codebase as permanent readiness gate

## Non-Goals (Explicitly Out of Scope)

- ❌ Multi-class DSL composition (Phase 2)
- ❌ Additional scenarios beyond systemd-adapter (come after pattern validation)
- ❌ Go bridge for Pulumi Automation API (not needed - scenarios run post-provisioning)
- ❌ External bdd-operator-manual module (scenarios are embedded in production code)
- ❌ Test-jar publication for stage sharing (defer until we have 2+ modules needing to share stages)

## Related Documentation

- [BDD Diagnostic Pattern](../bdd-diagnostic-pattern.adoc) - Full pattern catalog
- [Bootstrap Contract](../bootstrap-contract.adoc) - Verification stage integration
- [Fluent Pipeline Grammar](../fluent-pipeline-grammar.adoc) - Pipeline structure
- [Bootstrap Identity Provider](../bootstrap-identity-provider.adoc) - Context access from scenarios

## Implementation Notes

### Helper Method Implementation

Production helper methods use existing Incus/SSH infrastructure:

```java
private static String getSystemdUnitStatus(String unitName) {
  final ProcessBuilder pb = new ProcessBuilder(
      "ssh", config.imageBuilderHost(),
      String.format("incus exec %s -- systemctl status %s", config.nodeName(), unitName));
  // ... execute and capture output
}

private static List<String> getJournalLogs(String unitName) {
  final ProcessBuilder pb = new ProcessBuilder(
      "ssh", config.imageBuilderHost(),
      String.format("incus exec %s -- journalctl -u %s -n 50 --no-pager", config.nodeName(), unitName));
  // ... execute and parse lines
}

private static boolean checkPortListening(int port) {
  final ProcessBuilder pb = new ProcessBuilder(
      "ssh", config.imageBuilderHost(),
      String.format("incus exec %s -- ss -tuln | grep ':%d '", config.nodeName(), port));
  // ... execute and check exit code
}
```

### Shared Utility Classes

**SharedMetricsCollector:**
```java
public class SharedMetricsCollector {
  private final Map<String, Object> metrics = new LinkedHashMap<>();

  public void recordLatency(String probe, Duration elapsed) {
    metrics.put(probe + "LatencyMs", elapsed.toMillis());
  }

  public void captureFlag(String key, boolean value) {
    metrics.put(key, value);
  }

  public String toYaml() {
    // Jackson YAML serialization
  }
}
```

**SharedDecisionLog:**
```java
public class SharedDecisionLog {
  private String status;
  private final List<String> degradedScenarios = new ArrayList<>();
  private final List<String> blockingIssues = new ArrayList<>();

  public void recordHealthCheck(String component, boolean healthy) {
    if (!healthy) {
      degradedScenarios.add(component);
    }
  }

  public Map<String, Object> toJson() {
    return Map.of(
        "status", status,
        "shouldRollback", false,
        "canProceedToNextStage", degradedScenarios.isEmpty(),
        "degradedScenarios", degradedScenarios,
        "blockingIssues", blockingIssues);
  }
}
```

**SharedReportBuilder:**
```java
public class SharedReportBuilder {
  private final StringBuilder asciidoc = new StringBuilder();

  public SharedReportBuilder addSection(String title) {
    asciidoc.append("\n=== ").append(title).append("\n\n");
    return this;
  }

  public SharedReportBuilder addException(Exception e) {
    asciidoc.append("Error: ").append(e.getMessage()).append("\n\n");
    return this;
  }

  public SharedReportBuilder addDetail(String label, String content) {
    asciidoc.append("==== ").append(label).append("\n");
    asciidoc.append("```\n").append(content).append("\n```\n\n");
    return this;
  }

  public SharedReportBuilder addRemediation(String... steps) {
    asciidoc.append("=== Remediation\n\n");
    for (int i = 0; i < steps.length; i++) {
      asciidoc.append(i + 1).append(". ").append(steps[i]).append("\n");
    }
    return this;
  }

  public String toAsciiDoc() {
    return asciidoc.toString();
  }
}
```

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| JGiven scenarios slow down `pulumi up` | Scenarios run production code paths that would execute anyway; no significant overhead. Reports generate post-execution. |
| Nested class explosion | Strict naming convention prevents proliferation. Every production class has exactly: `DiagnosticCollector`, 3 actor implementations, `Stages`, `DiagnosticScenario`. Pattern is uniform. |
| SSH/incus exec failures in helpers | Helpers use same infrastructure as existing probes. If SSH fails, provisioning already failed - scenario just documents it. |
| Report format drift between actors | Shared utility classes (`SharedMetricsCollector`, `SharedDecisionLog`, `SharedReportBuilder`) ensure consistent formatting. Each actor calls the appropriate utility. |
| Pattern too complex for future developers | Full pattern documentation in `bdd-diagnostic-pattern.adoc`. Exemplar implementation in `SeedSystemdAdapterEndpointGate` serves as template. Co-location makes pattern visible. |

## Timeline Estimate

**Phase 1 (this spec):** 2-3 days

- Day 1: Maven dependencies, Severity enum, doctor hierarchy classes (GeneralistDiagnostic, Specialists), shared utilities
- Day 2: Nested BDD components in `SeedSystemdAdapterEndpointGate`, programmatic execution, severity + policy handling
- Day 3: Report publication, integration as readiness gate in provisioning code, testing against real infrastructure

**Phase 2 (DSL composition):** 1-2 days after pattern validation
