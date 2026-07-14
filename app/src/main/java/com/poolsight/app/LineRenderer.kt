package com.poolsight.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws world-space line segments (GL_LINES) — the table outline and grid in
 * Phase 1; aim lines and ball paths in later phases.
 */
class LineRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var viewProjectionUniform = 0
    private var colorUniform = 0

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_VERTICES * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        program = GlUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        viewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /**
     * Draw [vertexCount] vertices from [vertices] (x,y,z triples) as line
     * segments: vertices 0-1 one line, 2-3 the next, and so on.
     */
    fun draw(
        viewProjection: FloatArray,
        vertices: FloatArray,
        vertexCount: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float = 1f,
        widthPx: Float = 6f,
    ) {
        if (vertexCount < 2) return
        val clamped = minOf(vertexCount, MAX_VERTICES) and 0x7FFFFFFE // even count

        vertexBuffer.position(0)
        vertexBuffer.put(vertices, 0, clamped * 3)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(viewProjectionUniform, 1, false, viewProjection, 0)
        GLES20.glUniform4f(colorUniform, red, green, blue, alpha)
        GLES20.glLineWidth(widthPx)

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, clamped)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glLineWidth(1f)
    }

    private companion object {
        const val MAX_VERTICES = 1024

        const val VERTEX_SHADER = """
            uniform mat4 u_ViewProjection;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ViewProjection * a_Position;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
