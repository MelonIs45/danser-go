package pixconv

import "fmt"

type PixFmt int

const (
	ARGB PixFmt = iota
	RGB
	I420
	I422
	I444
	NV12
	NV21
)

func (t PixFmt) String() string {
	switch t {
	case ARGB:
		return "ARGB"
	case RGB:
		return "RGB"
	case I420:
		return "I420"
	case NV12:
		return "NV12"
	case NV21:
		return "NV21"
	case I422:
		return "I422"
	case I444:
		return "I444"
	}

	return "unknown"
}

func GetRequiredBufferSize(format PixFmt, w, h int) int {
	wH := w/2 + w%2
	hH := h/2 + h%2

	switch format {
	case ARGB:
		return w * h * 4
	case RGB:
		return w * h * 3
	case I420, NV12, NV21:
		return w*h + (wH*hH)*2
	case I422:
		return (w + wH*2) * h
	case I444:
		return w * h * 3
	}

	panic(fmt.Sprintf("Invalid pixel format: %s (%d)", format.String(), format))
}
