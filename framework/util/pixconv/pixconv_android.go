//go:build android

package pixconv

// libyuv isn't bundled on Android. Conversions are only needed for recording and
// background video decoding, neither of which runs there.
func Convert(_ []byte, inputFormat PixFmt, _ []byte, outputFormat PixFmt, _, _ int) {
	panic("pixconv: " + inputFormat.String() + " -> " + outputFormat.String() + " conversion is not available on Android")
}
