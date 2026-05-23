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
	floxEnvAnnotation   = "flox.dev/environment"
	floxDebugAnnotation = "flox.dev/debug"
	floxEnvBaseDir      = "/srv/host/k8s-daemonset.d/runtime/containerd-shim-flox"
	floxBinaryPath      = "/nix/store/2k9nn1y6yb7861swdkxr0arrcjiw7wpi-flox-1.12.1-g2078270/bin/flox"
)

// FloxPlugin implements the NRI plugin interface for flox environment injection
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

	// Resolve the flox environment path
	floxEnvPath, err := p.resolveFloxEnvironment(floxEnv)
	if err != nil {
		log.Printf("ERROR: Failed to resolve flox environment %s: %v", floxEnv, err)
		return nil, nil, fmt.Errorf("failed to resolve flox environment: %w", err)
	}

	log.Printf("Flox environment %s resolved to: %s", floxEnv, floxEnvPath)

	// Build container adjustments to inject the flox environment with overlayfs protection
	// Use overlayfs to layer a writable tmpfs over the read-only /nix/store
	// This protects the host's /nix/store from container modifications while
	// allowing containers to write to /nix (writes go to ephemeral tmpfs)
	adjustment := &api.ContainerAdjustment{
		Mounts: []*api.Mount{
			{
				// First, bind mount /nix/store read-only from host (will be lower layer)
				Source:      "/nix/store",
				Destination: "/nix-store-ro",
				Type:        "bind",
				Options:     []string{"ro", "rbind"},
			},
			{
				// Create tmpfs for overlayfs upper layer (writable, ephemeral)
				Destination: "/nix-overlay-upper",
				Type:        "tmpfs",
				Options:     []string{"mode=0755", "size=100m"},
			},
			{
				// Create tmpfs for overlayfs work directory
				Destination: "/nix-overlay-work",
				Type:        "tmpfs",
				Options:     []string{"mode=0755"},
			},
			{
				// Create overlay mount at /nix with lower=/nix-store-ro, upper=/nix-overlay-upper
				Source:      "overlay",
				Destination: "/nix",
				Type:        "overlay",
				Options:     []string{
					"lowerdir=/nix-store-ro",
					"upperdir=/nix-overlay-upper",
					"workdir=/nix-overlay-work",
				},
			},
		},

		// Inject environment variables for flox activation
		Env: []*api.KeyValue{
			{
				Key:   "FLOX_ENV_DIR",
				Value: floxEnvPath,
			},
			{
				Key:   "FLOX_BIN",
				Value: floxBinaryPath,
			},
			{
				Key:   "FLOX_ENV_NAME",
				Value: floxEnv,
			},
		},
	}

	return adjustment, nil, nil
}

// resolveFloxEnvironment resolves a flox environment name to its filesystem path
func (p *FloxPlugin) resolveFloxEnvironment(floxEnv string) (string, error) {
	// Parse flox environment
	// Format: "category/name" or just "name" (defaults to "networking" category)
	var category, envName string

	envParts := strings.Split(floxEnv, "/")
	if len(envParts) == 2 {
		category = envParts[0]
		envName = envParts[1]
	} else if len(envParts) == 1 {
		// Default to networking category
		category = "networking"
		envName = envParts[0]
	} else {
		return "", fmt.Errorf("invalid flox environment format: %s (expected 'category/name' or 'name')", floxEnv)
	}

	// Build path to flox environment directory
	floxEnvPath := filepath.Join(floxEnvBaseDir, category, envName)

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
