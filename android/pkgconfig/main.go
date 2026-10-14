// Command pkgconfig is a stand-in for pkg-config when cross-compiling for Android.
// go-gl's ES bindings ask pkg-config for "egl", which the NDK provides as plain -lEGL.
package main

import (
	"fmt"
	"os"
)

func main() {
	for _, arg := range os.Args[1:] {
		if arg == "--libs" {
			fmt.Println("-lEGL")
			return
		}
	}

	fmt.Println()
}
