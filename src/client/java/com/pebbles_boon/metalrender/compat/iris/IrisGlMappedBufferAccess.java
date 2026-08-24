package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;

/** Read-only view of Mojang's persistently mapped GL buffer storage. */
public interface IrisGlMappedBufferAccess {
  /**
   * Returns a duplicate of the current mapped storage, or {@code null} when
   * this buffer is not persistently mapped. The caller never mutates the
   * returned view.
   */
  ByteBuffer metalrender$mappedBufferView();
}
