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
	floxEnvAnnotation   = "flox.dev/environment" // Flox environment name (required)
	floxHomeAnnotation  = "flox.dev/home"        // Override HOME directory (optional, defaults to env var)
	floxUIDAnnotation   = "flox.dev/uid"         // Desired UID for flox env ownership (optional, default: 0)
	floxGIDAnnotation   = "flox.dev/gid"         // Desired GID for flox env ownership (optional, default: 0)
	floxDebugAnnotation = "flox.dev/debug"       // Set to "true" to pause and wait for debugger
	floxDebugPort       = "flox.dev/debug-port"  // Delve debugger port (default: 2345)
	floxEnvBaseDir      = "/srv/host/k8s-daemonset.d/runtime/flox-runtime"
	floxBinaryPath      = "/nix/store/2k9nn1y6yb7861swdkxr0arrcjiw7wpi-flox-1.12.1-g2078270/bin/flox"
	defaultDebugPort    = "2345"
	defaultUID          = "0"
	defaultGID          = "0"
	defaultHome         = "/root"
)

// FloxPlugin implements the NRI plugin interface for flox environment injection
//
// Supported annotations (can be set on pod or container):
//   - flox.dev/environment: Flox environment name (required, e.g., "networking/kdns")
//   - flox.dev/home: Override HOME directory (optional, defaults to HOME env var, then /root)
//   - flox.dev/uid: Desired UID for flox environment ownership (optional, default: 0/root)
//   - flox.dev/gid: Desired GID for flox environment ownership (optional, default: 0/root)
//   - flox.dev/debug: Set to "true" to enable debug mode (optional)
//   - flox.dev/debug-port: Delve debugger port (optional, default: 2345)
//
// Behavior:
//   - Determines HOME directory: flox.dev/home annotation > HOME env var > /root
//   - Resolves environment: "category/name" maps to /srv/host/.../runtime/flox-runtime/{category}/{name}
//   - Mounts the requested Flox environment at $HOME/.flox
//   - Mounts /nix/store with overlayfs protection (read-only lower, writable ephemeral upper)
//   - Allows `flox activate` to automatically discover the environment
//
// Note: Since NRI doesn't expose the container's UID, pods must set the HOME env var
//
//	to match the user they're running as (e.g., HOME=/root for UID 0, HOME=/home/user for others)
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
	// Check if we should wait for debugger attachment
	if getAnnotation(pod, floxDebugAnnotation) == "true" || getAnnotation(container, floxDebugAnnotation) == "true" {
		debugPort := getAnnotation(pod, floxDebugPort)
		if debugPort == "" {
			debugPort = getAnnotation(container, floxDebugPort)
		}
		if debugPort == "" {
			debugPort = defaultDebugPort
		}
		log.Printf("DEBUG MODE: Waiting for debugger on port %s for %s/%s", debugPort, pod.GetNamespace(), container.GetName())
		log.Printf("Attach with: dlv connect localhost:%s", debugPort)
		// In production, this would call runtime.Breakpoint() or similar
		// For now, just log and continue (debugger must be attached to the plugin process itself)
	}

	// Check if this container needs a flox environment
	floxEnv := getAnnotation(container, floxEnvAnnotation)
	if floxEnv == "" {
		// Also check pod annotations as fallback
		floxEnv = getAnnotation(pod, floxEnvAnnotation)
	}

	if floxEnv == "" {
		// No flox environment requested
		return nil, nil, nil
	}

	log.Printf("Container %s/%s requests flox environment: %s",
		pod.GetNamespace(), container.GetName(), floxEnv)

	// Create overlay mount point directories on host
	// These will be bind mounted into the container to ensure the directories exist
	// before overlay mounts are applied
	overlayMountPointsDir := "/srv/host/k8s-daemonset.d/runtime/flox-runtime/overlay-mount-points"
	if err := os.MkdirAll(filepath.Join(overlayMountPointsDir, "nix"), 0755); err != nil {
		return nil, nil, fmt.Errorf("failed to create overlay mount point /nix: %w", err)
	}
	if err := os.MkdirAll(filepath.Join(overlayMountPointsDir, "nix-store-ro"), 0755); err != nil {
		return nil, nil, fmt.Errorf("failed to create overlay mount point /nix-store-ro: %w", err)
	}
	log.Printf("Created overlay mount point directories on host at %s", overlayMountPointsDir)

	// Get UID/GID from container user (NRI v0.12.0+)
	// Priority: 1) Container.User, 2) annotations, 3) default to root
	var uid, gid string
	user := container.GetUser()
	if user != nil {
		uid = fmt.Sprintf("%d", user.GetUid())
		gid = fmt.Sprintf("%d", user.GetGid())
		log.Printf("Container user UID=%s, GID=%s", uid, gid)
	} else {
		// Fallback to annotations
		uid = getAnnotation(container, floxUIDAnnotation)
		if uid == "" {
			uid = getAnnotation(pod, floxUIDAnnotation)
		}
		if uid == "" {
			uid = defaultUID
		}

		gid = getAnnotation(container, floxGIDAnnotation)
		if gid == "" {
			gid = getAnnotation(pod, floxGIDAnnotation)
		}
		if gid == "" {
			gid = defaultGID
		}
		log.Printf("Using annotations/defaults: UID=%s, GID=%s", uid, gid)
	}

	// Determine HOME directory where .flox will be mounted
	// Priority: flox.dev/home annotation > HOME env var > infer from UID
	homeDir := getAnnotation(container, floxHomeAnnotation)
	if homeDir == "" {
		homeDir = getAnnotation(pod, floxHomeAnnotation)
	}
	if homeDir == "" {
		homeDir = getEnvVar(container, "HOME")
	}
	if homeDir == "" {
		// Infer home directory from UID
		if uid == "0" {
			homeDir = "/root"
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

	// Build mount options for the flox environment
	// Note: UID/GID mapping for bind mounts requires idmap support (Linux 5.12+, user namespaces)
	// For now, we log the desired ownership but rely on the source having correct permissions
	// Future enhancement: implement idmap mount or use a helper to chown at mount time
	floxMountOptions := []string{"ro", "rbind"}
	if uid != defaultUID || gid != defaultGID {
		log.Printf("WARNING: UID/GID mapping requested (uid=%s, gid=%s) but not yet implemented for bind mounts", uid, gid)
		log.Printf("The flox environment source must have correct ownership on the host filesystem")
		// TODO: Implement idmap mount option when available:
		// floxMountOptions = append(floxMountOptions, fmt.Sprintf("uidmap=%s:0:1", uid), fmt.Sprintf("gidmap=%s:0:1", gid))
	}

	// Create Prestart hook to ensure HOME and .flox have correct ownership
	// Runs after mounts but before container starts
	hookCmd := fmt.Sprintf("chown %s:%s '%s' '%s/.flox' 2>/dev/null || true", uid, gid, homeDir, homeDir)
	log.Printf("Creating Prestart hook for ownership: %s", hookCmd)

	chownHook := &api.Hook{
		Path: "/bin/sh",
		Args: []string{
			"sh",
			"-c",
			hookCmd,
		},
	}

	// Build container adjustments to inject the flox environment with overlayfs protection
	// Use overlayfs to layer a writable tmpfs over the read-only /nix/store
	// This protects the host's /nix/store from container modifications while
	// allowing containers to write to /nix (writes go to ephemeral tmpfs)

	floxEnvSource := filepath.Join(floxEnvPath, ".flox")
	log.Printf("Setting up mounts:")
	log.Printf("  - Flox environment: %s -> %s (bind, %v)", floxEnvSource, floxMountTarget, floxMountOptions)
	log.Printf("  - Overlay /nix: lowerdir=/nix-store-ro (host /nix/store), upperdir=/nix-overlay-upper (tmpfs), workdir=/nix-overlay-work (tmpfs)")
	log.Printf("  - Mount point dirs from: %s", overlayMountPointsDir)

	log.Printf("Adding Prestart hook (runs AFTER mounts are applied)")

	adjustment := &api.ContainerAdjustment{
		Hooks: &api.Hooks{
			Prestart: []*api.Hook{chownHook},
		},
		Mounts: []*api.Mount{
			{
				// Mount the flox environment at $HOME/.flox
				// This allows `flox activate` to automatically find the environment
				Source:      floxEnvSource,
				Destination: floxMountTarget,
				Type:        "bind",
				Options:     floxMountOptions,
			},
			{
				// First, bind mount empty directories to create mount points
				// This ensures /nix and /nix-store-ro exist in the container rootfs
				Source:      filepath.Join(overlayMountPointsDir, "nix"),
				Destination: "/nix",
				Type:        "bind",
				Options:     []string{"ro"},
			},
			{
				Source:      filepath.Join(overlayMountPointsDir, "nix-store-ro"),
				Destination: "/nix-store-ro",
				Type:        "bind",
				Options:     []string{"ro"},
			},
			{
				// Now bind mount the actual /nix/store from host to /nix-store-ro
				// This overwrites the empty directory we just mounted
				Source:      "/nix/store",
				Destination: "/nix-store-ro",
				Type:        "bind",
				Options:     []string{"ro", "rbind"},
			},
			{
				// tmpfs for overlay upperdir (writable layer)
				Destination: "/nix-overlay-upper",
				Type:        "tmpfs",
				Options:     []string{"mode=0755", "size=200m"},
			},
			{
				// tmpfs for overlay workdir
				Destination: "/nix-overlay-work",
				Type:        "tmpfs",
				Options:     []string{"mode=0755", "size=10m"},
			},
			{
				// Overlay mount at /nix
				// This overwrites the empty /nix directory mount with the overlay
				Source:      "overlay",
				Destination: "/nix",
				Type:        "overlay",
				Options: []string{
					"lowerdir=/nix-store-ro",
					"upperdir=/nix-overlay-upper",
					"workdir=/nix-overlay-work",
				},
			},
		},
	}

	log.Printf("Successfully configured Flox environment injection for container %s/%s", pod.GetNamespace(), container.GetName())

	return adjustment, nil, nil
}

// resolveFloxEnvironment resolves a flox environment name to its filesystem path
func (p *FloxPlugin) resolveFloxEnvironment(floxEnv string) (string, error) {
	// Parse flox environment
	// Format: "category/name" (e.g., "networking/kdns")
	// Matches the filesystem layout: /srv/host/k8s-daemonset.d/runtime/flox-runtime/{category}/{name}

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
