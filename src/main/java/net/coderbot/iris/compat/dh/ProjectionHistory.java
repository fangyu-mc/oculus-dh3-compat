package net.coderbot.iris.compat.dh;

/** Render-thread history, advanced once per frame, not once per DH pass. */
final class ProjectionHistory {
	private final float[] current = new float[16];
	private final float[] previous = new float[16];
	private boolean valid;
	private int frame;

	ProjectionHistory() { reset(); }

	void update(int nextFrame, float[] matrix) {
		if (!valid || (nextFrame != frame && nextFrame != (frame + 1) % 720720)) {
			System.arraycopy(matrix, 0, previous, 0, 16);
		} else if (nextFrame != frame) {
			System.arraycopy(current, 0, previous, 0, 16);
		}
		System.arraycopy(matrix, 0, current, 0, 16);
		frame = nextFrame;
		valid = true;
	}

	float[] previous() { return previous; }

	void reset() {
		java.util.Arrays.fill(current, 0);
		java.util.Arrays.fill(previous, 0);
		for (int i = 0; i < 16; i += 5) current[i] = previous[i] = 1;
		valid = false;
	}
}
