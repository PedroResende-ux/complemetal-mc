package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisShaderCaptureQueueTest {
  @Test
  void queueIsCountBoundedAndDeduplicatesAcrossPolling() {
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(3, 100, 200);
    IrisFinalShaderProgram first = graphics("first", "v1", "f1");
    IrisFinalShaderProgram second = graphics("second", "v2", "f2");
    IrisFinalShaderProgram third = graphics("third", "v3", "f3");

    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(first).disposition());
    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(first).disposition());
    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(second).disposition());
    assertEquals(IrisShaderCaptureQueue.Disposition.FULL,
        queue.offer(third).disposition());
    assertEquals(3, queue.size());
    assertEquals(1, queue.rejectedPrograms());

    assertEquals("first", queue.poll().orElseThrow().program().programName());
    assertEquals("second", queue.poll().orElseThrow().program().programName());
    assertFalse(queue.poll().isPresent());
    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(third).disposition());
  }

  @Test
  void queueIsAlsoBoundedByRetainedSourceCharacters() {
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(4, 20, 25);

    assertEquals(IrisShaderCaptureQueue.Disposition.TOO_LARGE,
        queue.offer(graphics("name", "12345678901234567890", "f"))
            .disposition());
    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(graphics("one", "123456", "abcdef")).disposition());
    assertEquals(IrisShaderCaptureQueue.Disposition.FULL,
        queue.offer(graphics("two", "123456", "abcdef")).disposition());

    assertEquals(2, queue.rejectedPrograms());
    assertTrue(queue.queuedChars() <= 25);
    assertTrue(queue.poll().isPresent());
    assertEquals(0, queue.queuedChars());
    assertFalse(queue.poll().isPresent());
  }

  private static IrisFinalShaderProgram graphics(String name, String vertex,
      String fragment) {
    return IrisFinalShaderProgram.fromGraphicsLink(name, vertex, null, null,
        null, fragment);
  }
}
