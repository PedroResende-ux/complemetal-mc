package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.stream.Collectors;
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

  @Test
  void packsManyPlainUniformsIntoAnArgumentBuffer() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    StringBuilder uniforms = new StringBuilder();
    StringBuilder sum = new StringBuilder();
    for (int index = 0; index < 40; index++) {
      uniforms.append("uniform float u").append(index).append(";\n");
      if (index > 0) {
        sum.append(" + ");
      }
      sum.append("u").append(index);
    }
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("uniform-pressure-smoke",
            """
            #version 450
            void main() {
              gl_Position = vec4(0.0);
            }
            """,
            null, null, null,
            "#version 450\nlayout(location = 0) out vec4 outColor;\n"
                + uniforms
                + "void main() { outColor = vec4("
                + sum + "); }\n");

    String msl = backend.translate(program)
        .stage(IrisShaderStage.FRAGMENT).msl();

    assertTrue(msl.contains("spvDescriptorSet"));
    assertTrue(msl.contains("[[buffer(0)]]"));
    assertFalse(msl.matches("(?s).*\\[\\[buffer\\((?:3[1-9]|[4-9][0-9])\\)\\]\\].*"));
  }

  @Test
  void translatesExactIrisCenterDepthProgram() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromProgramBuilderGraphics(
            "centerDepthSmooth",
            """
            #version 150 core
            in vec3 iris_Position;
            uniform mat4 projection;
            void main() {
              gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """,
            null,
            """
            #version 150 core
            uniform sampler2D depth;
            uniform sampler2D altDepth;
            uniform float lastFrameTime;
            uniform float decay;
            out float iris_fragColor;
            void main() {
              float currentDepth = texture(depth, vec2(0.5)).r;
              float decay2 = 1.0 - exp(-decay * lastFrameTime);
              float oldDepth = texture(altDepth, vec2(0.5)).r;
              if (isnan(oldDepth)) {
                oldDepth = currentDepth;
              }
              iris_fragColor = mix(oldDepth, currentDepth, decay2);
            }
            """);

    IrisShaderTranslation translation = backend.translate(program);

    assertStage(translation.stage(IrisShaderStage.VERTEX), "vertex");
    assertStage(translation.stage(IrisShaderStage.FRAGMENT), "fragment");
  }

  @Test
  void retainsResourceNamesNeededByTheRuntimeBindingBridge()
      throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("resource-name-smoke",
            """
            #version 450
            layout(std140) uniform CameraData {
              mat4 projection;
            } cameraData;
            void main() {
              gl_Position = cameraData.projection * vec4(0.0, 0.0, 0.0, 1.0);
            }
            """,
            null, null, null,
            """
            #version 450
            uniform sampler2D albedoSampler;
            uniform float exposure;
            layout(location = 0) out vec4 outColor;
            void main() {
              outColor = texture(albedoSampler, vec2(0.5)) * exposure;
            }
            """);

    IrisShaderTranslation translation = backend.translate(program);
    IrisSpirvResourceLayout vertex = reflect(
        translation.stage(IrisShaderStage.VERTEX).spirv());
    IrisSpirvResourceLayout fragment = reflect(
        translation.stage(IrisShaderStage.FRAGMENT).spirv());

    assertTrue(vertex.diagnosticNamesComplete());
    assertTrue(fragment.diagnosticNamesComplete());
    assertTrue(names(vertex).contains("CameraData"), names(vertex).toString());
    assertTrue(names(fragment).contains("albedoSampler"),
        names(fragment).toString());
    assertTrue(names(fragment).contains("exposure"),
        names(fragment).toString());
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

  private static IrisSpirvResourceLayout reflect(byte[] spirv) {
    IrisSpirvResourceReflector.ReflectionResult reflected =
        IrisSpirvResourceReflector.reflect(spirv);
    assertTrue(reflected.successful(), reflected.detail());
    return reflected.layout().orElseThrow();
  }

  private static Set<String> names(IrisSpirvResourceLayout layout) {
    return layout.diagnosticNames().values().stream()
        .collect(Collectors.toUnmodifiableSet());
  }
}
