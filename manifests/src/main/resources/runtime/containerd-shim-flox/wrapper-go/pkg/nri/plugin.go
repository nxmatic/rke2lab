package nri

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/containerd/nri/pkg/api"
	"github.com/containerd/nri/pkg/stub"
)

const (
	floxEnvAnnotation = "flox.dev/environment"
	floxDebugAnnotation = "flox.dev/debug"
	floxEnvCacheDir   = "/var/lib/flox/nri-cache"
)

// FloxPlugin implements the NRI plugin interface for flox environment injection
type FloxPlugin struct {
	stub stub.Stub
	ctx  context.Context
}

// NewFloxPlugin creates a new flox NRI plugin instance
func NewFloxPlugin(ctx context.Context) (*FloxPlugin, error) {
	// Ensure cache directory exists
	if err := os.MkdirAll(floxEnvCacheDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create flox cache dir: %w", err)
	}

	return &FloxPlugin{
		ctx: ctx,
	}, nil
}

// Configure handles plugin configuration (required by NRI)
func (p *FloxPlugin) Configure(ctx context.Context, config string) (stub.EventMask, error) {
	log.Printf("Flox NRI plugin configured")

	// Subscribe to container lifecycle events
	return api.MustParseEventMask(
		"RunPodSandbox",
		"CreateContainer",
		"StartContainer",
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

	// Activate/prepare the flox environment on the host
	floxRoot, err := p.activateFloxEnvironment(floxEnv)
	if err != nil {
		log.Printf("ERROR: Failed to activate flox environment %s: %v", floxEnv, err)
		return nil, nil, fmt.Errorf("failed to activate flox environment: %w", err)
	}

	log.Printf("Flox environment %s activated at: %s", floxEnv, floxRoot)

	// Build container adjustments to inject the flox environment
	adjustment := &api.ContainerAdjustment{
		// Use overlayfs to layer flox environment over the container's rootfs
		Mounts: []*api.Mount{
			{
				// Mount flox bin directory
				Source:      filepath.Join(floxRoot, "bin"),
				Destination: "/flox/bin",
				Type:        "bind",
				Options:     []string{"ro", "rbind"},
			},
			{
				// Mount flox lib directory
				Source:      filepath.Join(floxRoot, "lib"),
				Destination: "/flox/lib",
				Type:        "bind",
				Options:     []string{"ro", "rbind"},
			},
		},

		// Inject environment variables for flox
		Env: []*api.KeyValue{
			{
				Key:   "PATH",
				Value: "/flox/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
			},
			{
				Key:   "LD_LIBRARY_PATH",
				Value: "/flox/lib",
			},
			{
				Key:   "FLOX_ENV",
				Value: floxEnv,
			},
		},
	}

	return adjustment, nil, nil
}

// activateFloxEnvironment activates a flox environment and returns its root path
func (p *FloxPlugin) activateFloxEnvironment(floxEnv string) (string, error) {
	// Parse flox environment (format: owner/name or just name)
	envParts := strings.Split(floxEnv, "/")
	var envName string
	if len(envParts) == 2 {
		envName = envParts[0] + "-" + envParts[1]
	} else {
		envName = envParts[0]
	}

	// Check if environment is already cached
	envCachePath := filepath.Join(floxEnvCacheDir, envName)
	if _, err := os.Stat(envCachePath); err == nil {
		log.Printf("Using cached flox environment: %s", envCachePath)
		return envCachePath, nil
	}

	log.Printf("Activating flox environment: %s", floxEnv)

	// TODO: For now, assume flox environments are pre-activated at known locations
	// In production, this should call `flox activate` or pull from FloxHub
	// For the POC, we'll use the existing flox environment structure from the shim

	// Temporary: use a well-known path based on environment name
	// This matches the structure created by the shim installer
	floxEnvPath := fmt.Sprintf("/srv/host/k8s-daemonset.d/runtime/containerd-shim-flox/networking/%s", envName)

	if _, err := os.Stat(floxEnvPath); os.IsNotExist(err) {
		return "", fmt.Errorf("flox environment not found: %s", floxEnvPath)
	}

	// Create symlink in cache for faster lookups
	if err := os.Symlink(floxEnvPath, envCachePath); err != nil {
		log.Printf("Warning: failed to create cache symlink: %v", err)
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
