//go:build android

package gl

/*
#cgo LDFLAGS: -lEGL
#include <EGL/egl.h>
#include <stdlib.h>

static void* danserGetProcAddress(const char* name) {
	return (void*)eglGetProcAddress(name);
}
*/
import "C"

import (
	"unsafe"

	gl "github.com/go-gl/gl/v3.1/gles2"
)

// Desktop-only enums that danser references. They are never sent to an ES driver
// (the code paths using them are desktop/recording only), but they have to compile.
const (
	BGR                  = 0x80E0
	BGRA                 = 0x80E1
	GEOMETRY_SHADER      = 0x8DD9
	MAP_PERSISTENT_BIT   = 0x0040
	MAP_COHERENT_BIT     = 0x0080
	SRC1_ALPHA           = 0x8589
	SRC1_COLOR           = 0x88F9
	ONE_MINUS_SRC1_COLOR = 0x88FA
	ONE_MINUS_SRC1_ALPHA = 0x88FB
)

// IsGLES reports whether danser runs on top of OpenGL ES instead of desktop OpenGL.
const IsGLES = true

type textureInfo struct {
	target         uint32
	internalFormat uint32
	levels         int32
	width, height  int32
	layers         int32
}

type vertexBinding struct {
	buffer  uint32
	offset  int
	stride  int32
	divisor uint32
}

type vaoState struct {
	bindings map[uint32]*vertexBinding
}

var (
	currentDrawFBO uint32
	currentReadFBO uint32
	currentVAO     uint32

	scratchUnit uint32 = 31
	maxSamples  int32  = 4

	scratchReadFBO uint32
	scratchDrawFBO uint32

	textures = make(map[uint32]*textureInfo)
	vaos     = make(map[uint32]*vaoState)

	// CPU-side copies standing in for persistently mapped buffers, which ES 3.1 doesn't have.
	persistentShadows = make(map[uint32][]byte)
)

// Init loads OpenGL ES function pointers through EGL.
func Init() error {
	err := gl.InitWithProcAddrFunc(func(name string) unsafe.Pointer {
		cName := C.CString(name)
		defer C.free(unsafe.Pointer(cName))

		return C.danserGetProcAddress(cName)
	})

	if err != nil {
		return err
	}

	var units int32
	gl.GetIntegerv(gl.MAX_COMBINED_TEXTURE_IMAGE_UNITS, &units)

	if units > 0 {
		scratchUnit = uint32(min(units, 32) - 1)
	}

	gl.GetIntegerv(gl.MAX_SAMPLES, &maxSamples)

	gl.GenFramebuffers(1, &scratchReadFBO)
	gl.GenFramebuffers(1, &scratchDrawFBO)

	return nil
}

// PersistentFlush uploads the given byte range of an emulated persistent buffer to the GPU.
func PersistentFlush(buffer uint32, offset, size int) {
	shadow, ok := persistentShadows[buffer]
	if !ok || size <= 0 {
		return
	}

	gl.BindBuffer(gl.COPY_WRITE_BUFFER, buffer)
	gl.BufferSubData(gl.COPY_WRITE_BUFFER, offset, size, unsafe.Pointer(&shadow[offset]))
}

// ---- Binding tracking ----

func BindFramebuffer(target uint32, framebuffer uint32) {
	switch target {
	case gl.FRAMEBUFFER:
		currentDrawFBO = framebuffer
		currentReadFBO = framebuffer
	case gl.DRAW_FRAMEBUFFER:
		currentDrawFBO = framebuffer
	case gl.READ_FRAMEBUFFER:
		currentReadFBO = framebuffer
	}

	gl.BindFramebuffer(target, framebuffer)
}

func BindVertexArray(array uint32) {
	currentVAO = array
	gl.BindVertexArray(array)
}

func withDrawFBO(fbo uint32, f func()) {
	gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, fbo)
	f()
	gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, currentDrawFBO)
}

func withVAO(vao uint32, f func()) {
	gl.BindVertexArray(vao)
	f()
	gl.BindVertexArray(currentVAO)
}

// withoutScissor runs f with scissor test disabled; DSA clears/copies ignore scissor, ES clears/blits don't.
func withoutScissor(f func()) {
	scissor := gl.IsEnabled(gl.SCISSOR_TEST)
	if scissor {
		gl.Disable(gl.SCISSOR_TEST)
	}

	f()

	if scissor {
		gl.Enable(gl.SCISSOR_TEST)
	}
}

// ---- Buffers ----

func CreateBuffers(n int32, buffers *uint32) {
	gl.GenBuffers(n, buffers)
}

func DeleteBuffers(n int32, buffers *uint32) {
	for _, b := range unsafe.Slice(buffers, n) {
		delete(persistentShadows, b)
	}

	gl.DeleteBuffers(n, buffers)
}

func NamedBufferData(buffer uint32, size int, data unsafe.Pointer, usage uint32) {
	gl.BindBuffer(gl.COPY_WRITE_BUFFER, buffer)
	gl.BufferData(gl.COPY_WRITE_BUFFER, size, data, usage)
}

func NamedBufferSubData(buffer uint32, offset int, size int, data unsafe.Pointer) {
	gl.BindBuffer(gl.COPY_WRITE_BUFFER, buffer)
	gl.BufferSubData(gl.COPY_WRITE_BUFFER, offset, size, data)
}

func NamedBufferStorage(buffer uint32, size int, data unsafe.Pointer, flags uint32) {
	gl.BindBuffer(gl.COPY_WRITE_BUFFER, buffer)
	gl.BufferData(gl.COPY_WRITE_BUFFER, size, data, gl.DYNAMIC_DRAW)

	if flags&MAP_PERSISTENT_BIT > 0 {
		persistentShadows[buffer] = make([]byte, size)
	}
}

func MapNamedBufferRange(buffer uint32, offset int, length int, access uint32) unsafe.Pointer {
	if shadow, ok := persistentShadows[buffer]; ok {
		return unsafe.Pointer(&shadow[offset])
	}

	panic("gl: non-persistent buffer mapping is not supported on OpenGL ES")
}

// ---- Textures ----

func CreateTextures(target uint32, n int32, ids *uint32) {
	gl.GenTextures(n, ids)

	gl.ActiveTexture(gl.TEXTURE0 + scratchUnit)

	for _, id := range unsafe.Slice(ids, n) {
		textures[id] = &textureInfo{target: target}
		gl.BindTexture(target, id)
	}
}

func DeleteTextures(n int32, ids *uint32) {
	for _, id := range unsafe.Slice(ids, n) {
		delete(textures, id)
	}

	gl.DeleteTextures(n, ids)
}

func texTarget(id uint32) uint32 {
	if info, ok := textures[id]; ok {
		return info.target
	}

	return gl.TEXTURE_2D_ARRAY
}

func bindScratch(id uint32) uint32 {
	target := texTarget(id)

	gl.ActiveTexture(gl.TEXTURE0 + scratchUnit)
	gl.BindTexture(target, id)

	return target
}

func esInternalFormat(format uint32) uint32 {
	switch format {
	case BGRA:
		return gl.RGBA8
	case BGR:
		return gl.RGB8
	}

	return format
}

func esFormat(format uint32) uint32 {
	switch format {
	case BGRA:
		return gl.RGBA
	case BGR:
		return gl.RGB
	}

	return format
}

func TextureStorage3D(texture uint32, levels int32, internalformat uint32, width int32, height int32, depth int32) {
	if info, ok := textures[texture]; ok {
		info.internalFormat = esInternalFormat(internalformat)
		info.levels = levels
		info.width = width
		info.height = height
		info.layers = depth
	}

	target := bindScratch(texture)
	gl.TexStorage3D(target, levels, esInternalFormat(internalformat), width, height, depth)
}

func TextureParameteri(texture uint32, pname uint32, param int32) {
	if info, ok := textures[texture]; ok && isDepthFormat(info.internalFormat) && (pname == gl.TEXTURE_MIN_FILTER || pname == gl.TEXTURE_MAG_FILTER) {
		param = gl.NEAREST // depth textures aren't filterable in ES; LINEAR makes them incomplete (sample as 0)
	}

	target := bindScratch(texture)
	gl.TexParameteri(target, pname, param)
}

func TextureSubImage3D(texture uint32, level int32, xoffset int32, yoffset int32, zoffset int32, width int32, height int32, depth int32, format uint32, xtype uint32, pixels unsafe.Pointer) {
	target := bindScratch(texture)
	gl.TexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, esFormat(format), xtype, pixels)
}

func GenerateTextureMipmap(texture uint32) {
	if info, ok := textures[texture]; ok && (info.internalFormat == gl.RGBA32F || info.internalFormat == gl.RGB32F) {
		return // float textures aren't filterable in ES without extensions
	}

	target := bindScratch(texture)
	gl.GenerateMipmap(target)
}

func BindTextureUnit(unit uint32, texture uint32) {
	gl.ActiveTexture(gl.TEXTURE0 + unit)
	gl.BindTexture(texTarget(texture), texture)
}

func isDepthFormat(format uint32) bool {
	return format == gl.DEPTH_COMPONENT32F || format == gl.DEPTH_COMPONENT24 || format == gl.DEPTH_COMPONENT16 || format == gl.DEPTH_COMPONENT
}

func ClearTexImage(texture uint32, level int32, format uint32, xtype uint32, data unsafe.Pointer) {
	info, ok := textures[texture]
	if !ok {
		return
	}

	var value [4]float32

	if data != nil {
		components := 4
		switch format {
		case gl.RED, gl.DEPTH_COMPONENT:
			components = 1
		case gl.RG:
			components = 2
		case gl.RGB, BGR:
			components = 3
		}

		if xtype == gl.FLOAT {
			copy(value[:], unsafe.Slice((*float32)(data), components))
		} else {
			for i, b := range unsafe.Slice((*uint8)(data), components) {
				value[i] = float32(b) / 255
			}
		}

		if format == BGR || format == BGRA {
			value[0], value[2] = value[2], value[0]
		}
	}

	depth := isDepthFormat(info.internalFormat)

	withoutScissor(func() {
		withDrawFBO(scratchDrawFBO, func() {
			for layer := int32(0); layer < max(info.layers, 1); layer++ {
				if depth {
					gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.DEPTH_ATTACHMENT, texture, level, layer)

					var mask bool
					gl.GetBooleanv(gl.DEPTH_WRITEMASK, &mask)
					gl.DepthMask(true)
					gl.ClearBufferfv(gl.DEPTH, 0, &value[0])
					gl.DepthMask(mask)
				} else {
					gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, texture, level, layer)
					gl.ClearBufferfv(gl.COLOR, 0, &value[0])
				}
			}

			gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, 0, 0, 0)
			gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.DEPTH_ATTACHMENT, 0, 0, 0)
		})
	})
}

// CopyImageSubData is emulated with a blit per layer. Only color textures are supported, which is all danser copies.
func CopyImageSubData(srcName uint32, srcTarget uint32, srcLevel int32, srcX int32, srcY int32, srcZ int32, dstName uint32, dstTarget uint32, dstLevel int32, dstX int32, dstY int32, dstZ int32, srcWidth int32, srcHeight int32, srcDepth int32) {
	withoutScissor(func() {
		gl.BindFramebuffer(gl.READ_FRAMEBUFFER, scratchReadFBO)
		gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, scratchDrawFBO)

		for i := int32(0); i < srcDepth; i++ {
			gl.FramebufferTextureLayer(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, srcName, srcLevel, srcZ+i)
			gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, dstName, dstLevel, dstZ+i)

			gl.BlitFramebuffer(srcX, srcY, srcX+srcWidth, srcY+srcHeight, dstX, dstY, dstX+srcWidth, dstY+srcHeight, gl.COLOR_BUFFER_BIT, gl.NEAREST)
		}

		gl.FramebufferTextureLayer(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, 0, 0, 0)
		gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, 0, 0, 0)

		gl.BindFramebuffer(gl.READ_FRAMEBUFFER, currentReadFBO)
		gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, currentDrawFBO)
	})
}

func GetTextureSubImage(texture uint32, level int32, xoffset int32, yoffset int32, zoffset int32, width int32, height int32, depth int32, format uint32, xtype uint32, bufSize int32, pixels unsafe.Pointer) {
	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, scratchReadFBO)
	gl.FramebufferTextureLayer(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, texture, level, zoffset)
	gl.ReadPixels(xoffset, yoffset, width, height, esFormat(format), xtype, pixels)
	gl.FramebufferTextureLayer(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, 0, 0, 0)
	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, currentReadFBO)
}

// ---- Framebuffers and renderbuffers ----

func CreateFramebuffers(n int32, framebuffers *uint32) {
	gl.GenFramebuffers(n, framebuffers)
}

func DeleteFramebuffers(n int32, framebuffers *uint32) {
	for _, f := range unsafe.Slice(framebuffers, n) {
		if f == currentDrawFBO {
			currentDrawFBO = 0
		}

		if f == currentReadFBO {
			currentReadFBO = 0
		}
	}

	gl.DeleteFramebuffers(n, framebuffers)
}

func CreateRenderbuffers(n int32, renderbuffers *uint32) {
	gl.GenRenderbuffers(n, renderbuffers)
}

func esRenderbufferFormat(format uint32) uint32 {
	if format == gl.DEPTH_COMPONENT {
		return gl.DEPTH_COMPONENT24 // ES requires a sized depth format
	}

	return esInternalFormat(format)
}

func NamedRenderbufferStorage(renderbuffer uint32, internalformat uint32, width int32, height int32) {
	gl.BindRenderbuffer(gl.RENDERBUFFER, renderbuffer)
	gl.RenderbufferStorage(gl.RENDERBUFFER, esRenderbufferFormat(internalformat), width, height)
}

func NamedRenderbufferStorageMultisample(renderbuffer uint32, samples int32, internalformat uint32, width int32, height int32) {
	gl.BindRenderbuffer(gl.RENDERBUFFER, renderbuffer)
	gl.RenderbufferStorageMultisample(gl.RENDERBUFFER, min(samples, maxSamples), esRenderbufferFormat(internalformat), width, height)
}

func NamedFramebufferTextureLayer(framebuffer uint32, attachment uint32, texture uint32, level int32, layer int32) {
	withDrawFBO(framebuffer, func() {
		gl.FramebufferTextureLayer(gl.DRAW_FRAMEBUFFER, attachment, texture, level, layer)
	})
}

func NamedFramebufferRenderbuffer(framebuffer uint32, attachment uint32, renderbuffertarget uint32, renderbuffer uint32) {
	withDrawFBO(framebuffer, func() {
		gl.FramebufferRenderbuffer(gl.DRAW_FRAMEBUFFER, attachment, renderbuffertarget, renderbuffer)
	})
}

func NamedFramebufferDrawBuffers(framebuffer uint32, n int32, bufs *uint32) {
	withDrawFBO(framebuffer, func() {
		gl.DrawBuffers(n, bufs)
	})
}

func NamedFramebufferDrawBuffer(framebuffer uint32, buf uint32) {
	if framebuffer == 0 {
		return // the default framebuffer in ES only has BACK
	}

	withDrawFBO(framebuffer, func() {
		gl.DrawBuffers(1, &buf)
	})
}

func NamedFramebufferReadBuffer(framebuffer uint32, src uint32) {
	if framebuffer == 0 {
		return
	}

	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, framebuffer)
	gl.ReadBuffer(src)
	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, currentReadFBO)
}

func BlitNamedFramebuffer(readFramebuffer uint32, drawFramebuffer uint32, srcX0 int32, srcY0 int32, srcX1 int32, srcY1 int32, dstX0 int32, dstY0 int32, dstX1 int32, dstY1 int32, mask uint32, filter uint32) {
	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, readFramebuffer)
	gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, drawFramebuffer)

	gl.BlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)

	gl.BindFramebuffer(gl.READ_FRAMEBUFFER, currentReadFBO)
	gl.BindFramebuffer(gl.DRAW_FRAMEBUFFER, currentDrawFBO)
}

func ClearNamedFramebufferfv(framebuffer uint32, buffer uint32, drawbuffer int32, value *float32) {
	withDrawFBO(framebuffer, func() {
		gl.ClearBufferfv(buffer, drawbuffer, value)
	})
}

// ---- Vertex arrays ----

func CreateVertexArrays(n int32, arrays *uint32) {
	gl.GenVertexArrays(n, arrays)

	for _, a := range unsafe.Slice(arrays, n) {
		vaos[a] = &vaoState{bindings: make(map[uint32]*vertexBinding)}
	}
}

func DeleteVertexArrays(n int32, arrays *uint32) {
	for _, a := range unsafe.Slice(arrays, n) {
		delete(vaos, a)

		if a == currentVAO {
			currentVAO = 0
		}
	}

	gl.DeleteVertexArrays(n, arrays)
}

func getBinding(vao, index uint32) *vertexBinding {
	state, ok := vaos[vao]
	if !ok {
		state = &vaoState{bindings: make(map[uint32]*vertexBinding)}
		vaos[vao] = state
	}

	b, ok := state.bindings[index]
	if !ok {
		b = new(vertexBinding)
		state.bindings[index] = b
	}

	return b
}

func EnableVertexArrayAttrib(vaobj uint32, index uint32) {
	withVAO(vaobj, func() {
		gl.EnableVertexAttribArray(index)
	})
}

func VertexArrayAttribBinding(vaobj uint32, attribindex uint32, bindingindex uint32) {
	withVAO(vaobj, func() {
		gl.VertexAttribBinding(attribindex, bindingindex)
	})
}

func VertexArrayAttribFormat(vaobj uint32, attribindex uint32, size int32, xtype uint32, normalized bool, relativeoffset uint32) {
	withVAO(vaobj, func() {
		gl.VertexAttribFormat(attribindex, size, xtype, normalized, relativeoffset)
	})
}

func VertexArrayVertexBuffer(vaobj uint32, bindingindex uint32, buffer uint32, offset int, stride int32) {
	b := getBinding(vaobj, bindingindex)
	b.buffer = buffer
	b.offset = offset
	b.stride = stride

	withVAO(vaobj, func() {
		gl.BindVertexBuffer(bindingindex, buffer, offset, stride)
	})
}

func VertexArrayBindingDivisor(vaobj uint32, bindingindex uint32, divisor uint32) {
	getBinding(vaobj, bindingindex).divisor = divisor

	withVAO(vaobj, func() {
		gl.VertexBindingDivisor(bindingindex, divisor)
	})
}

func VertexArrayElementBuffer(vaobj uint32, buffer uint32) {
	withVAO(vaobj, func() {
		gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffer)
	})
}

// ---- Draws ----

// shiftInstanceBindings emulates baseInstance by offsetting every instanced binding of the bound VAO.
func shiftInstanceBindings(baseInstance uint32, apply bool) {
	if baseInstance == 0 {
		return
	}

	state, ok := vaos[currentVAO]
	if !ok {
		return
	}

	for index, b := range state.bindings {
		if b.divisor == 0 {
			continue
		}

		offset := b.offset
		if apply {
			offset += int(baseInstance) * int(b.stride)
		}

		gl.BindVertexBuffer(index, b.buffer, offset, b.stride)
	}
}

func DrawArraysInstancedBaseInstance(mode uint32, first int32, count int32, instancecount int32, baseinstance uint32) {
	shiftInstanceBindings(baseinstance, true)
	gl.DrawArraysInstanced(mode, first, count, instancecount)
	shiftInstanceBindings(baseinstance, false)
}

func DrawElementsInstancedBaseInstance(mode uint32, count int32, xtype uint32, indices unsafe.Pointer, instancecount int32, baseinstance uint32) {
	shiftInstanceBindings(baseinstance, true)
	gl.DrawElementsInstanced(mode, count, xtype, indices, instancecount)
	shiftInstanceBindings(baseinstance, false)
}
