package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.List;
import java.util.Map;
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
    String vertexMsl = translation.stage(IrisShaderStage.VERTEX).msl();
    assertTrue(vertexMsl.matches(
        "(?s).*\\.gl_Position\\.y\\s*=\\s*-\\(.*\\.gl_Position\\.y\\).*"),
        vertexMsl);
    assertTrue(vertexMsl.matches(
        "(?s).*\\.gl_Position\\.z\\s*=\\s*\\(.*\\.gl_Position\\.z\\s*\\+\\s*"
            + ".*\\.gl_Position\\.w\\)\\s*\\*\\s*0\\.5.*"), vertexMsl);
  }

  @Test
  void optimizesOrdinaryMathButPreservesExplicitPreciseMath()
      throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("math-contract-smoke",
            """
            #version 450
            layout(location = 0) in vec3 inPosition;
            uniform float scale;
            void main() {
              gl_Position = vec4(inPosition * scale + vec3(0.25), 1.0);
            }
            """,
            null, null, null,
            """
            #version 450
            uniform float exposure;
            layout(location = 0) out vec4 outColor;
            void main() {
              precise float preserved = exposure * 0.5 + 0.25;
              outColor = vec4(preserved);
            }
            """);

    IrisShaderTranslation translation = backend.translate(program);
    String vertexMsl = translation.stage(IrisShaderStage.VERTEX).msl();
    String fragmentMsl = translation.stage(IrisShaderStage.FRAGMENT).msl();

    assertFalse(vertexMsl.contains("[[clang::optnone]]"), vertexMsl);
    assertFalse(vertexMsl.contains("spvFMul("), vertexMsl);
    assertTrue(fragmentMsl.contains("[[clang::optnone]]"), fragmentMsl);
    assertTrue(fragmentMsl.contains("spvFMul("), fragmentMsl);
  }

  @Test
  void remapsIrisLocationsAndWidensPackedVertexInputs() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("packed-input-smoke",
            """
            #version 450
            in vec3 iris_Position;
            in ivec3 iris_Entity;
            void main() {
              gl_Position = vec4(iris_Position
                  + vec3(iris_Entity) * 0.0001, 1.0);
            }
            """,
            null, null, null,
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(1.0); }
            """)
            .withVertexShaderInputs(List.of(
                new IrisVertexLayoutCapture.ShaderInput(
                    "iris_Position", 0,
                    new IrisPipelineState.DataFormat("rgb32-float")),
                new IrisVertexLayoutCapture.ShaderInput(
                    "iris_Entity", 6,
                    new IrisPipelineState.DataFormat("rgba16-uint"))));

    String msl = backend.translate(program)
        .stage(IrisShaderStage.VERTEX).msl();

    assertTrue(msl.matches(
        "(?s).*float3\\s+iris_Position\\s+\\[\\[attribute\\(0\\)\\]\\].*"),
        msl);
    assertTrue(msl.matches(
        "(?s).*uint4\\s+iris_Entity\\s+\\[\\[attribute\\(6\\)\\]\\].*"),
        msl);
    assertTrue(msl.contains("int3(in.iris_Entity.xyz)"), msl);
  }

  @Test
  void mapsNormalizedStorageToRawIntegerMetalInput() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    String vertex = """
        #version 450
        in uvec4 a_LightAndData;
        void main() {
          gl_Position = vec4(a_LightAndData) * 0.0001;
        }
        """;
    IrisVertexLayoutCapture.Layout layout =
        IrisVertexLayoutCapture.resolveShaderInputFormats(vertex,
            IrisVertexLayoutCapture.capture(VertexFormat.builder()
                .add("a_LightAndData", VertexFormatElement.register(
                    VertexFormatElement.findNextId(), 0,
                    VertexFormatElement.Type.UBYTE,
                    VertexFormatElement.Usage.COLOR, 4))
                .build(), true));
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("normalized-integer-smoke",
            vertex, null, null, null,
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(1.0); }
            """)
            .withVertexShaderInputs(layout.shaderInputs());

    String msl = backend.translate(program)
        .stage(IrisShaderStage.VERTEX).msl();

    assertEquals("rgba8-uint",
        layout.attributes().getFirst().format().cacheName());
    assertTrue(msl.matches(
        "(?s).*uint4\\s+a_LightAndData\\s+\\[\\[attribute\\(0\\)\\]\\].*"),
        msl);
  }

  @Test
  void preservesOpenGlDefaultComponentsForWiderShaderInput()
      throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink("default-components-smoke",
            """
            #version 450
            in vec4 Position;
            void main() { gl_Position = Position; }
            """,
            null, null, null,
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(1.0); }
            """)
            .withVertexShaderInputs(List.of(
                new IrisVertexLayoutCapture.ShaderInput("Position", 0,
                    new IrisPipelineState.DataFormat("rgb32-float"))));

    String msl = backend.translate(program)
        .stage(IrisShaderStage.VERTEX).msl();

    assertTrue(msl.contains("[[attribute(0)]]"), msl);
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

  @Test
  void reflectsExactSpirvCrossArgumentBufferIds() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program = IrisFinalShaderProgram.fromGraphicsLink(
        "argument-layout-smoke",
        """
        #version 450
        layout(location=0) in vec3 pos;
        uniform mat4 model;
        uniform float exposure;
        layout(std140,binding=3) uniform Camera { vec4 tint; } camera;
        layout(std430,binding=4) buffer Data { float values[]; } data;
        layout(binding=5) uniform sampler2D tex;
        void main() {
          gl_Position=model*vec4(pos,1)+camera.tint*exposure
              +texture(tex,vec2(.5))+vec4(data.values[0]);
        }
        """, null, null, null,
        """
        #version 450
        layout(location=0) out vec4 outColor;
        void main() { outColor=vec4(1); }
        """);
    byte[] spirv = backend.translate(program)
        .stage(IrisShaderStage.VERTEX).spirv();

    IrisMslArgumentLayoutReader.StageResult result =
        IrisMslArgumentLayoutReader.reflect(IrisShaderStage.VERTEX, spirv);
    assertTrue(result instanceof IrisMslArgumentLayoutReader.StageComplete,
        result.toString());
    IrisMslArgumentLayout.StageLayout layout =
        ((IrisMslArgumentLayoutReader.StageComplete) result).layout();
    Map<IrisSpirvResourceLayout.ResourceAddress,
        IrisMslArgumentLayout.ArgumentBinding> bindings =
        layout.bindings().stream().collect(Collectors.toMap(
            IrisMslArgumentLayout.ArgumentBinding::address,
            binding -> binding));

    assertEquals(1, bindings.get(
        new IrisSpirvResourceLayout.DescriptorAddress(0, 3)).primaryId());
    assertEquals(5, bindings.get(
        new IrisSpirvResourceLayout.DescriptorAddress(0, 4)).primaryId());
    IrisMslArgumentLayout.ArgumentBinding sampled = bindings.get(
        new IrisSpirvResourceLayout.DescriptorAddress(0, 5));
    assertEquals(3, sampled.primaryId());
    assertEquals(4, sampled.secondaryId());
    assertEquals(0, bindings.get(
        new IrisSpirvResourceLayout.UniformLocation(0, 1)).primaryId());
    assertEquals(2, bindings.get(
        new IrisSpirvResourceLayout.UniformLocation(0, 2)).primaryId());
  }

  @Test
  void reflectsSampledImageUsedByTextureQuery() throws Exception {
    assumeEnabled();
    LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
        LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
    IrisFinalShaderProgram program = IrisFinalShaderProgram.fromGraphicsLink(
        "texture-only-argument-smoke",
        """
        #version 450
        layout(location=0) in vec3 pos;
        layout(binding=2) uniform isampler2D sectionTimeInfo;
        void main() {
          int value=textureSize(sectionTimeInfo,0).x;
          gl_Position=vec4(pos+vec3(float(value)*0.000001),1);
        }
        """, null, null, null,
        """
        #version 450
        layout(location=0) out vec4 outColor;
        void main() { outColor=vec4(1); }
        """);
    byte[] spirv = backend.translate(program)
        .stage(IrisShaderStage.VERTEX).spirv();

    IrisMslArgumentLayoutReader.StageResult result =
        IrisMslArgumentLayoutReader.reflect(IrisShaderStage.VERTEX, spirv);
    assertTrue(result instanceof IrisMslArgumentLayoutReader.StageComplete,
        result.toString());
    IrisMslArgumentLayout.ArgumentBinding sampled =
        ((IrisMslArgumentLayoutReader.StageComplete) result).layout()
            .bindings().stream()
            .filter(binding -> binding.kind()
                == IrisSpirvResourceLayout.ResourceKind.SAMPLED_IMAGE)
            .findFirst().orElseThrow();
    assertTrue(sampled.primaryId() >= 0);
    assertTrue(sampled.secondaryId() < 0
        || sampled.secondaryId() != sampled.primaryId());
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
