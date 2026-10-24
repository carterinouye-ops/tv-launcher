package com.carter.glasslauncher.gl

import android.opengl.GLES20
import android.util.Log

/** Compile/link helpers. Failures are logged with the driver's own message, not swallowed. */
object GlShader {

    private const val TAG = "GlShader"

    fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vertex == 0 || fragment == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
