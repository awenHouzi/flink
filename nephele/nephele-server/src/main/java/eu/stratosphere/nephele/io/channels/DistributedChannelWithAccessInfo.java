package eu.stratosphere.nephele.io.channels;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import eu.stratosphere.nephele.fs.FileSystem;
import eu.stratosphere.nephele.fs.Path;

final class DistributedChannelWithAccessInfo implements ChannelWithAccessInfo {

	/**
	 * The logging object.
	 */
	private static final Log LOG = LogFactory.getLog(DistributedChannelWithAccessInfo.class);

	private final FileSystem fs;

	private final Path checkpointFile;

	private final DistributedFileChannel channel;

	private final AtomicLong reservedWritePosition;

	private final AtomicInteger referenceCounter;

	DistributedChannelWithAccessInfo(final FileSystem fs, final Path checkpointFile, final int bufferSize)
			throws IOException {

		this.fs = fs;
		this.checkpointFile = checkpointFile;
		this.channel = new DistributedFileChannel(fs, checkpointFile, bufferSize);
		this.reservedWritePosition = new AtomicLong(0);
		this.referenceCounter = new AtomicInteger(0);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public FileChannel getChannel() {

		return this.channel;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public FileChannel getAndIncrementReferences() {

		if (incrementReferences()) {
			return this.channel;
		} else {
			return null;
		}
	}

	@Override
	public ChannelWithPosition reserveWriteSpaceAndIncrementReferences(final int spaceToReserve) {

		if (incrementReferences()) {
			return new ChannelWithPosition(this.channel, this.reservedWritePosition.getAndAdd(spaceToReserve));
		} else {
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int decrementReferences() {

		int current = this.referenceCounter.get();
		while (true) {
			if (current <= 0) {
				// this is actually an error case, because the channel was deleted before
				throw new IllegalStateException("The references to the file were already at zero.");
			}

			if (current == 1) {
				// this call decrements to zero, so mark it as deleted
				if (this.referenceCounter.compareAndSet(current, Integer.MIN_VALUE)) {
					current = 0;
					break;
				}
			} else if (this.referenceCounter.compareAndSet(current, current - 1)) {
				current = current - 1;
				break;
			}
			current = this.referenceCounter.get();
		}

		if (current > 0) {
			return current;
		} else if (current == 0) {
			// delete the channel
			this.referenceCounter.set(Integer.MIN_VALUE);
			this.reservedWritePosition.set(Long.MIN_VALUE);
			try {
				this.channel.close();
				this.fs.delete(this.checkpointFile, false);
			} catch (IOException ioex) {
				if (LOG.isErrorEnabled())
					LOG.error("Error while closing spill file for file buffers: " + ioex.getMessage(), ioex);
			}
			return current;
		} else {
			throw new IllegalStateException("The references to the file were already at zero.");
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean incrementReferences() {

		int current = this.referenceCounter.get();
		while (true) {
			// check whether it was disposed in the meantime
			if (current < 0) {
				return false;
			}
			// atomically check and increment
			if (this.referenceCounter.compareAndSet(current, current + 1)) {
				return true;
			}
			current = this.referenceCounter.get();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void disposeSilently() {

		this.referenceCounter.set(Integer.MIN_VALUE);
		this.reservedWritePosition.set(Long.MIN_VALUE);

		if (this.channel.isOpen()) {
			try {
				this.channel.close();
				this.fs.delete(this.checkpointFile, false);
			} catch (Throwable t) {
			}
		}
	}
}
