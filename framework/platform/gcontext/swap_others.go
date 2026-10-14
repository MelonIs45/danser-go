//go:build !windows

package gcontext

import "github.com/Zyko0/go-sdl3/sdl"

func SetSwapInterval(interval int) {
	optional(sdl.GL_SetSwapInterval(int32(interval)))
}

func SwapBuffers() {
	// On Android the surface can disappear (app closed or backgrounded) before the loop notices
	optional(sdl.GL_SwapWindow(sdlWindow))
}
