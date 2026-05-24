package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/containerd/nri/pkg/stub"
	"github.com/nxmatic/rke2lab/containerd-shim-flox-wrapper/pkg/nri"
)

const (
	pluginName = "flox"
	pluginIdx  = "10" // Plugin execution order (lower runs first)
)

var (
	pluginSocket = "/var/run/nri/nri.sock" // Default for standalone mode
)

func main() {
	// Log to stdout/stderr (captured by container runtime)
	log.SetOutput(os.Stderr)
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	log.Println("=== Flox NRI Plugin Starting ===")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Handle signals for graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Println("Received shutdown signal, stopping plugin...")
		cancel()
	}()

	// Create the flox NRI plugin
	log.Println("Creating Flox NRI plugin instance...")
	plugin, err := nri.NewFloxPlugin(ctx)
	if err != nil {
		log.Fatalf("Failed to create flox NRI plugin: %v", err)
	}
	log.Println("Flox NRI plugin instance created successfully")

	// Create NRI stub (handles protocol communication with containerd)
	// If NRI_PLUGIN_SOCKET env var is set, stub will use that fd automatically
	// Otherwise it will connect to the socket path
	socketMode := "standalone"
	if os.Getenv("NRI_PLUGIN_SOCKET") != "" {
		socketMode = "containerd-launched"
	}
	log.Printf("Creating NRI stub (name=%s, idx=%s, mode=%s)...", pluginName, pluginIdx, socketMode)

	stubOpts := []stub.Option{
		stub.WithPluginName(pluginName),
		stub.WithPluginIdx(pluginIdx),
	}

	// Only set socket path if not launched by containerd
	// When containerd launches us, NRI_PLUGIN_SOCKET env var is set and stub handles it
	if os.Getenv("NRI_PLUGIN_SOCKET") == "" {
		log.Printf("Using socket path: %s", pluginSocket)
		stubOpts = append(stubOpts, stub.WithSocketPath(pluginSocket))
	} else {
		log.Printf("Using NRI_PLUGIN_SOCKET fd from containerd")
	}

	nriStub, err := stub.New(plugin, stubOpts...)
	if err != nil {
		log.Fatalf("Failed to create NRI stub: %v", err)
	}
	log.Println("NRI stub created successfully")

	// Start the plugin (this handles registration and Configure callback)
	log.Printf("Starting flox NRI plugin (socket: %s, index: %s)...", pluginSocket, pluginIdx)
	if err := nriStub.Start(ctx); err != nil {
		log.Fatalf("Failed to start plugin: %v", err)
	}
	log.Println("Plugin started and registered successfully")

	// Wait for plugin to stop (blocks until context cancelled or error)
	nriStub.Wait()

	log.Println("Flox NRI plugin stopped gracefully")
}
