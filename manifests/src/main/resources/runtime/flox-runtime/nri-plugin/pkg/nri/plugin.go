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
	floxEnvBaseDir            = "/srv/host/k8s-daemonset.d/runtime/flox-runtime/environment.d"
	floxOverlayHookPath       = "/usr/local/sbin/flox-nri-overlay-hook.sh"
	floxChownHookPath         = "/usr/local/sbin/flox-nri-chown-hook.sh"
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
//   - Resolves environment: "category/name" maps to /srv/host/.../runtime/flox-runtime/environment.d/{category}/{name}
//   - Mounts the requested Flox environment at $HOME/.flox
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

	log.Printf("Flox environment %s resolved to: %s", floxEnv, floxEnvPath)

	// Target path where we'll mount the flox environment: $HOME/.flox
	floxMountTarget := filepath.Join(homeDir, ".flox")
	floxEnvSource := filepath.Join(floxEnvPath, ".flox")

	if uid != defaultUID || gid != defaultGID {
		log.Printf("WARNING: UID/GID mapping requested (uid=%s, gid=%s) but not yet implemented", uid, gid)
		log.Printf("The flox environment source must have correct ownership on the host filesystem")
	}

	adjustment := &api.ContainerAdjustment{}

	// CreateContainer hooks set up overlayfs mounts in the container rootfs.
	// They run in the container's mount namespace before pivot_root, so the
	// mounts persist after pivot_root. CreateRuntime would NOT work here: it
	// runs in the host/runtime namespace, and the container ns is unshared with
	// rprivate propagation so host-side mounts don't reach the container.
	//
	// Each invocation creates scratch dirs under /.overlays.d/<name>/ in the
	// container rootfs (lower bind, single tmpfs hosting upper+work) and mounts
	// an overlayfs at the requested target.
	nixStoreOverlayHook := &api.Hook{
		Path: floxOverlayHookPath,
		Args: []string{floxOverlayHookPath, "nix-store", "/nix/store", "/nix/store"},
	}
	log.Printf("Adding CreateContainer hook (nix-store): %s /nix/store -> /nix/store", floxOverlayHookPath)

	floxEnvOverlayHook := &api.Hook{
		Path: floxOverlayHookPath,
		Args: []string{floxOverlayHookPath, "flox-env", floxEnvSource, floxMountTarget},
	}
	log.Printf("Adding CreateContainer hook (flox-env): %s %s -> %s", floxOverlayHookPath, floxEnvSource, floxMountTarget)

	// Prestart hook fixes ownership of $HOME and $HOME/.flox via ${bundle}/rootfs.
	// Runs in the host namespace; the container rootfs is reachable from there.
	chownHook := &api.Hook{
		Path: floxChownHookPath,
		Args: []string{floxChownHookPath, uid, gid, homeDir},
	}
	log.Printf("Adding Prestart hook: %s %s %s %s", floxChownHookPath, uid, gid, homeDir)

	adjustment.AddHooks(&api.Hooks{
		CreateContainer: []*api.Hook{nixStoreOverlayHook, floxEnvOverlayHook},
		Prestart:        []*api.Hook{chownHook},
	})

	log.Printf("Successfully configured Flox environment injection for container %s/%s", pod.GetNamespace(), container.GetName())

	return adjustment, nil, nil
}

// RemoveContainer is called when a container is removed
// No cleanup needed with simple bind mount approach
func (p *FloxPlugin) RemoveContainer(ctx context.Context, pod *api.PodSandbox, container *api.Container) error {
	return nil
}

// resolveFloxEnvironment resolves a flox environment name to its filesystem path
func (p *FloxPlugin) resolveFloxEnvironment(floxEnv string) (string, error) {
	// Parse flox environment
	// Format: "category/name" (e.g., "networking/kdns")
	// Matches the filesystem layout: /srv/host/k8s-daemonset.d/runtime/flox-runtime/environment.d/{category}/{name}

	var category, envName string

	envParts := strings.Split(floxEnv, "/")
	if len(envParts) == 2 {
		// category/name format
		category = envParts[0]
		envName = envParts[1]
	} else if len(envParts) == 1 {
		// Just name - default to networking category
		category = "networking"
		envName = envParts[0]
	} else {
		return "", fmt.Errorf("invalid flox environment format: %s (expected 'category/name' or 'name')", floxEnv)
	}

	// Build path to flox environment directory
	floxEnvPath := filepath.Join(floxEnvBaseDir, category, envName)

	log.Printf("Resolving Flox environment '%s' -> category=%s, name=%s, path=%s", floxEnv, category, envName, floxEnvPath)

	// Verify the environment directory exists
	if _, err := os.Stat(floxEnvPath); os.IsNotExist(err) {
		return "", fmt.Errorf("flox environment not found: %s (path: %s)", floxEnv, floxEnvPath)
	}

	// Verify it has a .flox directory
	floxMetaDir := filepath.Join(floxEnvPath, ".flox")
	if _, err := os.Stat(floxMetaDir); os.IsNotExist(err) {
		return "", fmt.Errorf("flox environment missing .flox metadata: %s", floxEnvPath)
	}

	return floxEnvPath, nil
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
