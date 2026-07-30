package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in native smoke test:
 *
 * <pre>
 * METALRENDER_IRIS_TRANSLATION_SMOKE=1 ./gradlew test \
 *   --tests '*.IrisInProcessTranslationSmokeTest'
 * </pre>
 *
 * When explicitly enabled, missing/broken natives fail the test rather than
 * being treated as a discovery success.
 */
final class IrisInProcessTranslationSmokeTest {
  private static final String ENABLED_PROPERTY =
      "metalrender.test.irisTranslationSmoke";
  private static final String ENABLED_ENVIRONMENT =
      "METALRENDER_IRIS_TRANSLATION_SMOKE";
  private static final int SPIRV_MAGIC = 0x07230203;

  @Test
  void translatesMinimalVertexAndFragmentToSpirvAndMsl()
      throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    assertTrue(backend.discover().available(), backend.discover().detail());

    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("smoke",
            """
            #version 450
            layout(location = 0) in vec3 inPosition;
            void main() {
              gl_Position = vec4(inPosition, 1.0);
            }
            """,
            null, null, null,
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
              outColor = vec4(1.0);
            }
            """);

    IrisShaderTranslation translation = backend.translate(program);

    assertEquals(2, translation.stages().size());
    assertStage(translation.stage(IrisShaderStage.VERTEX), "vertex");
    assertStage(translation.stage(IrisShaderStage.FRAGMENT), "fragment");
  }

  @Test
  void translatesSourceLargerThanLwjglThreadMemoryStack()
      throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    String largeComment = "/*" + "x".repeat(256 * 1024) + "*/\n";
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("large-smoke",
            "#version 450\n" + largeComment
                + "void main() { gl_Position = vec4(0.0); }\n",
            null, null, null,
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
              outColor = vec4(1.0);
            }
            """);

    IrisShaderTranslation translation = backend.translate(program);

    assertStage(translation.stage(IrisShaderStage.VERTEX), "vertex");
    assertStage(translation.stage(IrisShaderStage.FRAGMENT), "fragment");
  }

  private static void assumeEnabled() {
    Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY)
            || "1".equals(System.getenv(ENABLED_ENVIRONMENT)),
        "native translation smoke test is opt-in");
  }

  private static void assertStage(
      IrisShaderTranslation.StageArtifacts artifacts, String mslStage) {
    assertNotNull(artifacts);
    byte[] spirv = artifacts.spirv();
    assertTrue(spirv.length % Integer.BYTES == 0);
    assertEquals(SPIRV_MAGIC, ByteBuffer.wrap(spirv)
        .order(ByteOrder.LITTLE_ENDIAN).getInt());
    assertTrue(!artifacts.msl().isBlank());
    assertTrue(artifacts.msl().contains(mslStage));
    assertTrue(artifacts.msl().matches(
        "(?s).*\\bmain\\w*\\s*\\(.*"));
  }
}
