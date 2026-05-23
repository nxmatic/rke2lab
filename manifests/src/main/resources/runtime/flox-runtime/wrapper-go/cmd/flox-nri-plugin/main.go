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
	pluginName   = "flox"
	pluginIdx    = "10" // Plugin execution order (lower runs first)
	pluginSocket = "/var/run/nri/nri.sock"
)

func main() {
	// Setup file logging for debugging (NRI plugin stdout/stderr may not be captured)
	logFile, err := os.OpenFile("/var/log/flox-nri-plugin.log",
		os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		// Fall back to stderr if we can't open log file
		log.Printf("Warning: could not open log file: %v", err)
	} else {
		defer logFile.Close()
		log.SetOutput(logFile)
		log.SetFlags(log.LstdFlags | log.Lshortfile)
	}

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
	log.Printf("Creating NRI stub (name=%s, idx=%s, socket=%s)...", pluginName, pluginIdx, pluginSocket)
	nriStub, err := stub.New(plugin,
		stub.WithPluginName(pluginName),
		stub.WithPluginIdx(pluginIdx),
		stub.WithSocketPath(pluginSocket),
	)
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
