//go:build android

// Android entry point. Built with -buildmode=c-shared as libmain.so; SDL's SDLActivity
// loads it and calls SDL_main on its render thread with the arguments from DanserActivity.
package main

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>

static void danserLog(int prio, const char* msg) {
	__android_log_write(prio, "danser", msg);
}
*/
import "C"

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"runtime/debug"
	"unsafe"

	"github.com/wieku/danser-go/app"
	"github.com/wieku/danser-go/framework/env"
)

// pipeToLogcat redirects a standard stream into logcat, since Android discards stdout/stderr.
func pipeToLogcat(target **os.File, prio C.int) {
	r, w, err := os.Pipe()
	if err != nil {
		return
	}

	*target = w

	go func() {
		scanner := bufio.NewScanner(r)
		scanner.Buffer(make([]byte, 64*1024), 1024*1024)

		for scanner.Scan() {
			msg := C.CString(scanner.Text())
			C.danserLog(prio, msg)
			C.free(unsafe.Pointer(msg))
		}
	}()
}

//export SDL_main
func SDL_main(argc C.int, argv **C.char) (ret C.int) {
	pipeToLogcat(&os.Stdout, C.ANDROID_LOG_INFO)
	pipeToLogcat(&os.Stderr, C.ANDROID_LOG_ERROR)

	args := make([]string, 0, int(argc))
	for _, a := range unsafe.Slice(argv, int(argc)) {
		args = append(args, C.GoString(a))
	}

	// args[0] is the program name, args[1] the app's files directory, the rest are danser flags.
	if len(args) < 2 {
		fmt.Fprintln(os.Stderr, "danser: missing data directory argument")
		return 1
	}

	// The launcher shows crash.txt to the user when it isn't empty after danser exits.
	crashFile, _ := os.Create(filepath.Join(args[1], "crash.txt"))
	if crashFile != nil {
		_ = debug.SetCrashOutput(crashFile, debug.CrashOptions{}) // fatal errors and panics on other goroutines
	}

	defer func() {
		if err := recover(); err != nil {
			report := fmt.Sprintf("danser crashed: %v\n%s\n", err, debug.Stack())
			fmt.Fprint(os.Stderr, report)

			if crashFile != nil {
				_, _ = crashFile.WriteString(report)
				_ = crashFile.Sync()
			}

			ret = 1
		}
	}()

	env.InitAt(args[1])

	os.Args = append([]string{"danser"}, args[2:]...)

	app.Run()

	return 0
}

func main() {}
