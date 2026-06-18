package nri

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"

	"github.com/containerd/nri/pkg/api"
	"github.com/containerd/nri/pkg/stub"
)

const (
	// Per-container annotation prefixes. The plugin reads
	// "<prefix>.<container-name>" — there is NO fallback to the bare key or
	// to a pod-wide annotation. Each container in a pod opts in independently.
	floxEnvAnnotationPrefix   = "flox.dev/environment" // <prefix>.<container> = "category/name" (required to opt in)
	floxHomeAnnotationPrefix  = "flox.dev/home"        // <prefix>.<container> = override HOME (optional)
	floxUIDAnnotationPrefix   = "flox.dev/uid"         // <prefix>.<container> = desired UID (optional, default: 0)
	floxGIDAnnotationPrefix   = "flox.dev/gid"         // <prefix>.<container> = desired GID (optional, default: 0)
	floxDebugAnnotationPrefix = "flox.dev/debug"       // <prefix>.<container> = "true" to enable debug pause
	floxDebugPortPrefix       = "flox.dev/debug-port"  // <prefix>.<container> = delve port (default: 2345)
	// Store-resolved env handoff. The runtime installer realizes each env's
	// static subtree (env/{manifest.toml,manifest.lock}) into /nix/store and
	// GC-roots it at <floxEnvGcrootBase>/<category>/<name>. We readlink that
	// gcroot to get the immutable store path, then flox-nri-env-link-hook.sh
	// builds a fine-grained .flox symlink farm in the container (env/ -> store,
	// run/cache/log local). The container's /nix/store overlay lowers from the
	// host store, so the same store path resolves inside the container.
	// This supersedes the old /var/run env-dir copy + overlay-mount of .flox.
	floxEnvGcrootBase = "/nix/var/nix/gcroots/flox-runtime/env"
	floxOverlayHookPath       = "/usr/local/sbin/flox-nri-overlay-hook.sh"
	floxEnvLinkHookPath       = "/usr/local/sbin/flox-nri-env-link-hook.sh"
	floxChownHookPath         = "/usr/local/sbin/flox-nri-chown-hook.sh"
	// System-wide flox config maintained on the host by rke2lab-env-load.sh.
	// We bind-mount it read-only into every flox-injected container so the in-
	// container `flox` invocation honors host policy (telemetry off, channel
	// lock, ...) without each pod having to set FLOX_* env vars.
	floxSystemConfigPath      = "/etc/flox.toml"
	defaultDebugPort          = "2345"
	defaultUID                = "0"
	defaultGID                = "0"
	rootHome                  = "/root"
)

// FloxPlugin implements the NRI plugin interface for flox environment injection
//
// Annotations are PER-CONTAINER. The plugin reads
// "<prefix>.<container-name>" on the pod's annotations map. There is NO
// fallback to a bare "<prefix>" key — each container must opt in by name.
// This lets a pod mix flox-injected and unmodified containers freely.
//
// Supported keys (substitute <c> with the container name):
//   - flox.dev/environment.<c>: Flox environment "category/name" (required to opt in)
//   - flox.dev/home.<c>:        Override HOME directory (optional, defaults to HOME env var, then /root)
//   - flox.dev/uid.<c>:         Desired UID for flox env ownership (optional, default: 0)
//   - flox.dev/gid.<c>:         Desired GID for flox env ownership (optional, default: 0)
//   - flox.dev/debug.<c>:       Set to "true" to enable debug mode (optional)
//   - flox.dev/debug-port.<c>:  Delve debugger port (optional, default: 2345)
//
// Behavior:
//   - Determines HOME directory: flox.dev/home.<c> > HOME env var > /root
//   - Resolves environment: "category/name" -> the store path GC-rooted at
//     /nix/var/nix/gcroots/flox-runtime/env/{category}/{name} by the installer
//   - Materializes a fine-grained .flox symlink farm at $HOME/.flox (env/ -> store,
//     run/cache/log local) via flox-nri-env-link-hook.sh
//   - Mounts /nix/store with overlayfs protection (read-only lower, writable ephemeral upper)
//   - Allows `flox activate --dir $HOME` to automatically discover the environment
//
// Note: Since NRI doesn't expose the container's UID at the protocol level on
// older runtimes, pods may set the HOME env var to match the user they run as
// (e.g., HOME=/root for UID 0, HOME=/home/user for others).
type FloxPlugin struct {
	stub stub.Stub
	ctx  context.Context
}

// NewFloxPlugin creates a new flox NRI plugin instance
func NewFloxPlugin(ctx context.Context) (*FloxPlugin, error) {
	return &FloxPlugin{
		ctx: ctx,
	}, nil
}

// Configure handles plugin configuration (required by NRI)
func (p *FloxPlugin) Configure(ctx context.Context, config string) (stub.EventMask, error) {
	log.Printf("Flox NRI plugin configured")

	// Subscribe to container lifecycle events
	return api.MustParseEventMask(
		"CreateContainer",
	), nil
}

// Synchronize handles state sync after plugin restart (required by NRI)
func (p *FloxPlugin) Synchronize(ctx context.Context, pods []*api.PodSandbox, containers []*api.Container) ([]*api.ContainerUpdate, error) {
	log.Printf("Synchronizing state: %d pods, %d containers", len(pods), len(containers))
	return nil, nil
}

// Shutdown handles plugin shutdown (required by NRI)
func (p *FloxPlugin) Shutdown(ctx context.Context) {
	log.Println("Flox NRI plugin shutting down")
}

// CreateContainer handles container creation and injects flox environment
func (p *FloxPlugin) CreateContainer(ctx context.Context, pod *api.PodSandbox, container *api.Container) (*api.ContainerAdjustment, []*api.ContainerUpdate, error) {
	containerName := container.GetName()

	// Per-container annotation lookup. NO fallback: a container without
	// `flox.dev/environment.<container-name>` is not flox-injected, even if
	// a sibling container in the same pod has the annotation set.
	envKey := perContainerKey(floxEnvAnnotationPrefix, containerName)
	floxEnv := getAnnotation(pod, envKey)
	if floxEnv == "" {
		// Container did not opt in.
		return nil, nil, nil
	}

	log.Printf("Container %s/%s requests flox environment: %s (via %s)",
		pod.GetNamespace(), containerName, floxEnv, envKey)

	// Optional debug pause — also per-container.
	if getAnnotation(pod, perContainerKey(floxDebugAnnotationPrefix, containerName)) == "true" {
		debugPort := getAnnotation(pod, perContainerKey(floxDebugPortPrefix, containerName))
		if debugPort == "" {
			debugPort = defaultDebugPort
		}
		log.Printf("DEBUG MODE: Waiting for debugger on port %s for %s/%s", debugPort, pod.GetNamespace(), containerName)
		log.Printf("Attach with: dlv connect localhost:%s", debugPort)
	}

	// Get UID/GID from container user (NRI v0.12.0+)
	// Priority: 1) Container.User, 2) per-container annotation, 3) default to root
	var uid, gid string
	user := container.GetUser()
	if user != nil {
		uid = fmt.Sprintf("%d", user.GetUid())
		gid = fmt.Sprintf("%d", user.GetGid())
		log.Printf("Container user UID=%s, GID=%s", uid, gid)
	} else {
		uid = getAnnotation(pod, perContainerKey(floxUIDAnnotationPrefix, containerName))
		if uid == "" {
			uid = defaultUID
		}
		gid = getAnnotation(pod, perContainerKey(floxGIDAnnotationPrefix, containerName))
		if gid == "" {
			gid = defaultGID
		}
		log.Printf("Using annotations/defaults: UID=%s, GID=%s", uid, gid)
	}

	// Determine HOME where .flox will be mounted.
	// Priority: flox.dev/home.<c> > HOME env var > infer from UID
	homeDir := getAnnotation(pod, perContainerKey(floxHomeAnnotationPrefix, containerName))
	if homeDir == "" {
		homeDir = getEnvVar(container, "HOME")
	}
	if homeDir == "" {
		if uid == "0" {
			homeDir = rootHome
		} else {
			homeDir = fmt.Sprintf("/home/user-%s", uid)
		}
	}

	log.Printf("Using HOME directory: %s (will mount .flox at %s/.flox)", homeDir, homeDir)
	log.Printf("Flox environment ownership: UID=%s, GID=%s", uid, gid)

	// Resolve the flox environment path
	floxEnvPath, err := p.resolveFloxEnvironment(floxEnv)
	if err != nil {
		log.Printf("ERROR: Failed to resolve flox environment %s: %v", floxEnv, err)
		return nil, nil, fmt.Errorf("failed to resolve flox environment: %w", err)
	}

	log.Printf("Flox environment %s resolved to store path: %s", floxEnv, floxEnvPath)

	// Target path where we materialize the env's .flox symlink farm: $HOME/.flox
	floxMountTarget := filepath.Join(homeDir, ".flox")

	if uid != defaultUID || gid != defaultGID {
		log.Printf("WARNING: UID/GID mapping requested (uid=%s, gid=%s) but not yet implemented", uid, gid)
		log.Printf("The flox environment source must have correct ownership on the host filesystem")
	}

	adjustment := &api.ContainerAdjustment{}

	// CreateContainer hooks run in the container's mount namespace before
	// pivot_root, so what they write persists after pivot_root. CreateRuntime
	// would NOT work here: it runs in the host/runtime namespace, and the
	// container ns is unshared with rprivate propagation so host-side mounts
	// don't reach the container.
	//
	// 1) nix-store overlay: read-only host store lower + writable ephemeral
	//    tmpfs upper, so the container sees every host store path AND can author
	//    its own (new flox envs, profiles, builds land in the upper).
	nixStoreOverlayHook := &api.Hook{
		Path: floxOverlayHookPath,
		Args: []string{floxOverlayHookPath, "nix-store", "/nix/store", "/nix/store"},
	}
	log.Printf("Adding CreateContainer hook (nix-store): %s /nix/store -> /nix/store", floxOverlayHookPath)

	// 2) env-link: build the fine-grained .flox symlink farm — env/ symlinks
	//    into the resolved store path (immutable default env), run/cache/log are
	//    real local writable dirs. Replaces the old overlay-mount of .flox,
	//    which mutated/clamped the env tree and broke relative flake refs.
	floxEnvLinkHook := &api.Hook{
		Path: floxEnvLinkHookPath,
		Args: []string{floxEnvLinkHookPath, floxEnvPath, floxMountTarget},
	}
	log.Printf("Adding CreateContainer hook (env-link): %s %s -> %s", floxEnvLinkHookPath, floxEnvPath, floxMountTarget)

	// Prestart hook fixes ownership of $HOME and $HOME/.flox via ${bundle}/rootfs.
	// Runs in the host namespace; the container rootfs is reachable from there.
	chownHook := &api.Hook{
		Path: floxChownHookPath,
		Args: []string{floxChownHookPath, uid, gid, homeDir},
	}
	log.Printf("Adding Prestart hook: %s %s %s %s", floxChownHookPath, uid, gid, homeDir)

	adjustment.AddHooks(&api.Hooks{
		CreateContainer: []*api.Hook{nixStoreOverlayHook, floxEnvLinkHook},
		Prestart:        []*api.Hook{chownHook},
	})

	// Project host /etc/flox.toml into the container if the host has one. The
	// host file is the system-wide opt-out for flox telemetry (and any future
	// host-wide flox policy). Read-only — pods can't shadow it. Skipping
	// silently when the host file is absent keeps the plugin permissive on
	// nodes that haven't run rke2lab-env-load.sh yet.
	if _, err := os.Stat(floxSystemConfigPath); err == nil {
		adjustment.AddMount(&api.Mount{
			Source:      floxSystemConfigPath,
			Destination: floxSystemConfigPath,
			Type:        "bind",
			Options:     []string{"bind", "ro"},
		})
		log.Printf("Bind-mounted host %s into container", floxSystemConfigPath)
	}

	log.Printf("Successfully configured Flox environment injection for container %s/%s", pod.GetNamespace(), container.GetName())

	return adjustment, nil, nil
}

// RemoveContainer is called when a container is removed
// No cleanup needed with simple bind mount approach
func (p *FloxPlugin) RemoveContainer(ctx context.Context, pod *api.PodSandbox, container *api.Container) error {
	return nil
}

// resolveFloxEnvironment resolves a flox environment name to its immutable
// /nix/store path by reading the GC-root the runtime installer published.
//
// Format: "category/name" (e.g., "networking/kdns"). A bare "name" defaults to
// the "networking" category. The installer GC-roots each env at
// <floxEnvGcrootBase>/<category>/<name>; we readlink it to the real store path,
// which exposes <store-path>/env/{manifest.toml,manifest.lock}.
func (p *FloxPlugin) resolveFloxEnvironment(floxEnv string) (string, error) {
	var category, envName string

	envParts := strings.Split(floxEnv, "/")
	if len(envParts) == 2 {
		category = envParts[0]
		envName = envParts[1]
	} else if len(envParts) == 1 {
		category = "networking"
		envName = envParts[0]
	} else {
		return "", fmt.Errorf("invalid flox environment format: %s (expected 'category/name' or 'name')", floxEnv)
	}

	gcroot := filepath.Join(floxEnvGcrootBase, category, envName)

	storePath, err := os.Readlink(gcroot)
	if err != nil {
		return "", fmt.Errorf("flox environment %s not GC-rooted at %s: %w", floxEnv, gcroot, err)
	}

	// Confirm the resolved store path carries the env subtree the link hook expects.
	envSubtree := filepath.Join(storePath, "env")
	if _, err := os.Stat(envSubtree); err != nil {
		return "", fmt.Errorf("flox environment %s store path missing env/ subtree (%s): %w", floxEnv, envSubtree, err)
	}

	log.Printf("Resolving Flox environment '%s' -> category=%s, name=%s, store=%s", floxEnv, category, envName, storePath)

	return storePath, nil
}

// perContainerKey builds a per-container annotation key by suffixing the
// prefix with ".<container-name>". The plugin uses ONLY this form — there is
// no fallback to a bare prefix or a pod-wide annotation.
func perContainerKey(prefix, containerName string) string {
	return prefix + "." + containerName
}

// Helper to get annotation from container or pod
func getAnnotation(obj interface{}, key string) string {
	var annotations map[string]string

	switch v := obj.(type) {
	case *api.PodSandbox:
		annotations = v.GetAnnotations()
	case *api.Container:
		annotations = v.GetAnnotations()
	default:
		return ""
	}

	if annotations == nil {
		return ""
	}

	return annotations[key]
}

// getEnvVar retrieves an environment variable from the container spec
func getEnvVar(container *api.Container, key string) string {
	env := container.GetEnv()
	if env == nil {
		return ""
	}

	// GetEnv returns []string in "KEY=VALUE" format
	// We need to parse it
	prefix := key + "="
	for _, envStr := range env {
		if strings.HasPrefix(envStr, prefix) {
			return strings.TrimPrefix(envStr, prefix)
		}
	}

	return ""
}
