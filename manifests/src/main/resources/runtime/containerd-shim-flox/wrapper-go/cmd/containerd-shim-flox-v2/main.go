package main

import (
	"fmt"
	"os"

	"github.com/nxmatic/rke2lab/containerd-shim-flox-wrapper/internal/wrapper"
)

func main() {
	if err := wrapper.New().Run(os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}
