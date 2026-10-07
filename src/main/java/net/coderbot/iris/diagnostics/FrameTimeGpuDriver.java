package net.coderbot.iris.diagnostics;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/** Timestamp queries have no active query target, so they can coexist with other timer queries. */
final class FrameTimeGpuDriver implements FrameTimeGpuCapture.Driver {
	@Override
	public int timestampBits() {
		GLCapabilities caps = GL.getCapabilities();
		if (!caps.OpenGL33 && !caps.GL_ARB_timer_query) { return 0; }
		return GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS);
	}

	@Override public int create() { return GL15.glGenQueries(); }
	@Override public void timestamp(int query) { GL33.glQueryCounter(query, GL33.GL_TIMESTAMP); }
	@Override public boolean available(int query) {
		return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
	}
	@Override public long result(int query) { return GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); }
	@Override public void delete(int query) { GL15.glDeleteQueries(query); }
}
