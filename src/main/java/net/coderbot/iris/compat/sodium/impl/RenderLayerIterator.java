package net.coderbot.iris.compat.sodium.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * One cursor per chunk rebuild task, which is owned by a single build worker.
 * Only use for the non-escaping enhanced-for loops in performBuild: after their
 * final hasNext() returns false, the exhausted cursor can serve the next block.
 */
public final class RenderLayerIterator<T> extends UnmodifiableIterator<T> {

	private ImmutableList<T> layers;
	private int index;

	public Iterator<T> acquire(List<T> nextLayers) {
		// Preserve custom iterator/mutation semantics, including Embeddium's
		// uncached ArrayList path. Never overwrite a suspended/reentrant loop.
		if (layers != null || !(nextLayers instanceof ImmutableList)) {
			return nextLayers.iterator();
		}
		layers = (ImmutableList<T>) nextLayers;
		index = 0;
		return this;
	}

	@Override
	public boolean hasNext() {
		if (layers != null && index < layers.size()) {
			return true;
		}
		// Release only at the loop's exit check, not when next() returns the
		// last element: rendering that element can still make a nested call.
		layers = null;
		return false;
	}

	@Override
	public T next() {
		if (layers == null || index >= layers.size()) {
			throw new NoSuchElementException();
		}
		return layers.get(index++);
	}
}
