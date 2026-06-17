---
name: check-osgi-standard-before-modeling
description: "Before modeling any OSGi-ish mechanism (config, lifecycle, services, extenders), CHECK the OSGi standard against the real jars FIRST — don't invent a custom namespace/abstraction that the spec already provides. Born 2026-06-16/17: nearly reinvented Config Admin + Metatype + DS with a homegrown unitrepo.config.key resolution namespace."
metadata:
  node_type: memory
  type: feedback
---

When designing anything that touches OSGi concepts — configuration, service lifecycle,
capability/requirement namespaces, extenders, fragments — **verify the OSGi standard on the
actual jars on disk BEFORE modeling a custom mechanism.** The spec very often already provides
the thing you are about to invent.

**Why:** during the rke2lab Step-2 config design (see [[rke2lab:step2-decomposition-state]]) we
nearly shipped "Model B" — config keys as a custom `unitrepo.config.key` resolution namespace, each
key a Provide/Require capability the Felix resolver wires. A spike even proved it works mechanically.
Then the completeness review (grounded on the jars in `~/.m2/repository/org/osgi/`, not memory) showed
the standard already covers it, and BETTER:
- **Config Admin** (`org.osgi.service.cm`) delivers config VALUES at runtime by PID (push via
  `ManagedService.updated`), AFTER resolution — config values are NOT a resolution concern.
- **Metatype** (`org.osgi.service.metatype`) describes the config SCHEMA (`ObjectClassDefinition` +
  `AttributeDefinition`: type, cardinality, required/optional, default) — we had reinvented this as
  `InfraConfigFragment` + the typed keys in `InfraDomain.contribute`.
- **DS** (`org.osgi.service.component`) `configuration-policy=require` makes "no config → don't
  activate" an ACTIVATION concern (`UNSATISFIED_CONFIGURATION` is a runtime component state), NOT a
  resolution one — exactly the "loud fail on missing config" we wanted, at the right level.
- The bridge to resolution that DOES exist: `Require osgi.extender` (the delivery mechanism — DS =
  `osgi.component`, Metatype = `osgi.metatype`). `osgi.extender` is a real resolution namespace
  (`org.osgi.namespace.extender.ExtenderNamespace`, value `"osgi.extender"`, extends
  `org.osgi.resource.Namespace`). So the generic bundle↔host contract is OSGi-native (Require an
  extender, host Provides it), not the invented per-key namespace. Re-spiked at that grain, green.

**How to apply:** the OSGi API jars are on disk under `~/.m2/repository/org/osgi/` (osgi.core,
org.osgi.service.cm, .metatype, .component, org.osgi.namespace.extender, …). Use `unzip -p <jar>
META-INF/MANIFEST.MF` (Provide/Require-Capability) and `javap -constants -classpath <jar> <fqcn>`
(namespace constants, their string values) to read the REAL contract. Fetch a missing spec jar with
`./mvnw dependency:get -Dartifact=org.osgi:<id>:<ver>`. Distinguish the three OSGi planes and put each
concern on the right one: **resolution** (static, the resolver — capabilities/requirements incl.
osgi.extender), **delivery** (runtime — Config Admin push), **activation** (DS configuration-policy).
This is the thesis discipline already recorded for docrepo: APPLY OSGi's principles, don't bypass them
([[rke2lab:docrepo-dag-state]] "OSGi reuse is standalone … verified vs primary sources").

Watch the vocabulary collision too: an OSGi **fragment** is a host-attached bundle (`Fragment-Host`,
`osgi.wiring.host`), NOT a config DTO — `InfraConfigFragment` is misnamed (it's an
`ObjectClassDefinition`). See [[brainstorm-vocabulary-view-first]].
