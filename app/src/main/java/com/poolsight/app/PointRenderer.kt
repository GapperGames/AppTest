package com.poolsight.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws world-space points as fixed-size dots — Phase 0's plane markers.
 * Later phases reuse this for ball highlights and contact-spot dots.
 */
class PointRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var viewProjectionUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0

    private var vertexBuffer: FloatBuffer = allocate(MAX_POINTS * 3)

    fun createOnGlThread() {
        program = GlUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        viewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    /** Draw [count] points from [positions] (x,y,z triples, world space). */
    fun draw(
        viewProjection: FloatArray,
        positions: FloatArray,
        count: Int,
        red: Float = 0.42f,
        green: Float = 0.90f,
        blue: Float = 0.55f,
        pointSizePx: Float = 36f,
    ) {
        if (count == 0) return
        val clamped = minOf(count, MAX_POINTS)

        vertexBuffer.position(0)
        vertexBuffer.put(positions, 0, clamped * 3)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(viewProjectionUniform, 1, false, viewProjection, 0)
        GLES20.glUniform4f(colorUniform, red, green, blue, 0.95f)
        GLES20.glUniform1f(pointSizeUniform, pointSizePx)

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, clamped)
        GLES20.glDisableVertexAttribArray(positionAttrib)
    }

    private fun allocate(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private companion object {
        const val MAX_POINTS = 64

        const val VERTEX_SHADER = """
            uniform mat4 u_ViewProjection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ViewProjection * a_Position;
                gl_PointSize = u_PointSize;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                // Round dot: discard fragments outside the unit circle.
                vec2 d = gl_PointCoord - vec2(0.5);
                if (dot(d, d) > 0.25) discard;
                gl_FragColor = u_Color;
            }
        """
    }
}
