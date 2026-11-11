package com.custom.hisenselauncher.wallpaper

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** Anything the GL renderer can draw: it fills [tris] and [lines] (screen pixels) once per frame. */
interface GLScene {
    val tris: VertexBuffer
    val lines: VertexBuffer
    fun frame(w: Float, h: Float, t: Float, dt: Float)
}

/** The jellyfish aquarium (wallpaper + screensaver). */
class JellyfishView(ctx: Context, brightness: Float = 1f) : GLSceneView(ctx, JellyScene(brightness))

/**
 * Hardware-rendered scene (OpenGL ES 2, 4x MSAA when available, vsync-paced).
 * Geometry comes from a [GLScene]; this class only uploads and draws it in two batched calls,
 * blended additively over black.
 */
open class GLSceneView(ctx: Context, scene: GLScene) : GLSurfaceView(ctx) {

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(SceneRenderer(scene))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private class MsaaChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es2 = 4 // EGL_OPENGL_ES2_BIT
            val msaa = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, es2, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE)
            val plain = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, es2, EGL10.EGL_NONE)
            for (attrs in listOf(msaa, plain)) {
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, null, 0, num) && num[0] > 0) {
                    val configs = arrayOfNulls<EGLConfig>(num[0])
                    egl.eglChooseConfig(display, attrs, configs, num[0], num)
                    configs.firstOrNull()?.let { return it }
                }
            }
            throw IllegalStateException("No EGL config")
        }
    }

    private class SceneRenderer(private val scene: GLScene) : Renderer {
        private var program = 0
        private var aPos = 0; private var aCol = 0; private var aUvs = 0; private var uRes = 0
        private var w = 1f; private var h = 1f
        private val start = SystemClock.uptimeMillis()
        private var last = 0L
        private var frames = 0
        private var cpuNs = 0L
        private var statStart = SystemClock.uptimeMillis()
        private val triBuf: FloatBuffer = direct(scene.tris.data.size)
        private val lineBuf: FloatBuffer = direct(scene.lines.data.size)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VS, FS)
            aPos = GLES20.glGetAttribLocation(program, "a_pos")
            aCol = GLES20.glGetAttribLocation(program, "a_col")
            aUvs = GLES20.glGetAttribLocation(program, "a_uvs")
            uRes = GLES20.glGetUniformLocation(program, "u_res")
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)   // additive glow on black
            val range = FloatArray(2)
            GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
            GLES20.glLineWidth(1.4f.coerceIn(range[0], maxOf(range[0], range[1])))
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            w = width.toFloat(); h = height.toFloat()
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0f, 0.05f)
            last = now
            val t0 = System.nanoTime()
            scene.frame(w, h, (now - start) / 1000f, dt)
            cpuNs += System.nanoTime() - t0
            frames++
            if (now - statStart >= 5000) {
                android.util.Log.d("JellyRenderer", "%s fps=%.1f sceneCpu=%.2fms verts=%d/%d".format(
                    scene.javaClass.simpleName, frames * 1000f / (now - statStart), cpuNs / 1e6f / frames, scene.tris.count, scene.lines.count))
                frames = 0; cpuNs = 0L; statStart = now
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform2f(uRes, w, h)
            draw(triBuf, scene.tris, GLES20.GL_TRIANGLES)
            draw(lineBuf, scene.lines, GLES20.GL_LINES)
        }

        private fun draw(buf: FloatBuffer, vb: VertexBuffer, mode: Int) {
            if (vb.count == 0) return
            buf.clear()
            buf.put(vb.data, 0, vb.count * VertexBuffer.STRIDE)
            val stride = VertexBuffer.STRIDE * 4
            buf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(aPos)
            buf.position(2)
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(aCol)
            buf.position(6)
            GLES20.glVertexAttribPointer(aUvs, 3, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(aUvs)
            GLES20.glDrawArrays(mode, 0, vb.count)
        }

        private fun direct(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun link(vs: String, fs: String): Int {
            fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }

        companion object {
            private const val VS = """
                attribute vec2 a_pos;
                attribute vec4 a_col;
                attribute vec3 a_uvs;
                uniform vec2 u_res;
                varying vec4 v_col;
                varying vec3 v_uvs;
                void main() {
                    vec2 n = a_pos / u_res * 2.0 - 1.0;
                    gl_Position = vec4(n.x, -n.y, 0.0, 1.0);
                    v_col = a_col;
                    v_uvs = a_uvs;
                }
            """
            // soft = 1 -> radial falloff from the quad's uv (halo / glow sprite)
            private const val FS = """
                precision mediump float;
                varying vec4 v_col;
                varying vec3 v_uvs;
                void main() {
                    float f = 1.0 - clamp(length(v_uvs.xy), 0.0, 1.0);
                    float m = mix(1.0, f * f, v_uvs.z);
                    gl_FragColor = v_col * m;
                }
            """
        }
    }
}
