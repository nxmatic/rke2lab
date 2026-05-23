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
	pluginName  = "flox"
	pluginIdx   = "10" // Plugin execution order (lower runs first)
	pluginSocket = "/var/run/nri/nri.sock"
)

func main() {
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
	plugin, err := nri.NewFloxPlugin(ctx)
	if err != nil {
		log.Fatalf("Failed to create flox NRI plugin: %v", err)
	}

	// Create NRI stub (handles protocol communication with containerd)
	nriStub, err := stub.New(plugin,
		stub.WithPluginName(pluginName),
		stub.WithPluginIdx(pluginIdx),
		stub.WithSocketPath(pluginSocket),
	)
	if err != nil {
		log.Fatalf("Failed to create NRI stub: %v", err)
	}

	// Start the plugin
	log.Printf("Starting flox NRI plugin (socket: %s, index: %s)...", pluginSocket, pluginIdx)
	if err := nriStub.Run(ctx); err != nil {
		log.Fatalf("Plugin stopped with error: %v", err)
	}

	log.Println("Flox NRI plugin stopped")
}
