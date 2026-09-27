package net.coderbot.iris.compat.dh;

public final class ProjectionHistoryCheck {
	public static void main(String[] args) {
		ProjectionHistory history = new ProjectionHistory();
		float[] matrix = new float[16];
		matrix[0] = 2;
		history.update(100, matrix);
		check(history.previous()[0] == 2, "first frame");
		matrix[0] = 3;
		history.update(101, matrix);
		check(history.previous()[0] == 2, "next frame");
		matrix[0] = 4;
		history.update(101, matrix);
		check(history.previous()[0] == 2, "transparent pass must not advance history");
		history.update(102, matrix);
		check(history.previous()[0] == 4, "copy rather than alias");
		matrix[0] = 5;
		history.update(110, matrix);
		check(history.previous()[0] == 5, "frame gap");
		history.reset();
		check(history.previous()[0] == 1, "reset identity");
		history.update(720719, matrix);
		matrix[0] = 6;
		history.update(0, matrix);
		check(history.previous()[0] == 5, "counter rollover");
		System.out.println("Projection history checks passed");
	}
	private static void check(boolean ok, String reason) {
		if (!ok) throw new AssertionError(reason);
	}
}
