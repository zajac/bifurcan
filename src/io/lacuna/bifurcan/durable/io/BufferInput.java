package io.lacuna.bifurcan.durable.io;

import io.lacuna.bifurcan.DurableInput;
import io.lacuna.bifurcan.durable.Bytes;

import java.nio.ByteBuffer;

/**
 * An implementation of {@link DurableInput} atop {@link ByteBuffer}.
 *
 * @author ztellman
 */
public class BufferInput implements DurableInput {

  private final ByteBuffer buffer;
  private final Bounds bounds;
  private final Runnable closeFn;

  public BufferInput(ByteBuffer buffer) {
    this(buffer, new Bounds(null, 0, buffer.remaining()), null);
  }

  public BufferInput(ByteBuffer buffer, Runnable closeFn) {
    this(buffer, new Bounds(null, 0, buffer.remaining()), closeFn);
  }

  public BufferInput(ByteBuffer buffer, Bounds bounds, Runnable closeFn) {
    this.buffer = buffer;
    this.bounds = bounds;
    this.closeFn = closeFn;
  }

  @Override
  public void close() {
    if (closeFn != null) {
      closeFn.run();
    }
  }

  @Override
  public Pool pool() {
    return () -> this.duplicate().seek(0);
  }

  @Override
  public Bounds bounds() {
    return bounds;
  }

  @Override
  public DurableInput slice(long start, long end) {
    if (start < 0 || end > size() || end < start) {
      throw new IllegalArgumentException(String.format("[%d, %d) is not within [0, %d)", start, end, size()));
    }

    return new BufferInput(Bytes.slice(buffer, start, end), new Bounds(bounds, start, end), null);
  }

  @Override
  public DurableInput duplicate() {
    return new BufferInput(Bytes.duplicate(buffer), bounds, closeFn);
  }

  @Override
  public DurableInput seek(long position) {
    buffer.position((int) position);
    return this;
  }

  @Override
  public long remaining() {
    return buffer.remaining();
  }

  @Override
  public long position() {
    return buffer.position();
  }

  @Override
  public int read(ByteBuffer dst) {
    return Bytes.transfer(buffer, dst);
  }

  @Override
  public byte readByte() {
    return buffer.get();
  }

  @Override
  public short readShort() {
    return buffer.getShort();
  }

  @Override
  public char readChar() {
    return buffer.getChar();
  }

  @Override
  public int readInt() {
    return buffer.getInt();
  }

  @Override
  public long readLong() {
    return buffer.getLong();
  }

  @Override
  public float readFloat() {
    return buffer.getFloat();
  }

  @Override
  public double readDouble() {
    return buffer.getDouble();
  }
}
