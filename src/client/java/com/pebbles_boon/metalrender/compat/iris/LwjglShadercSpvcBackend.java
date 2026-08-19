package com.pebbles_boon.metalrender.compat.iris;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_opengl_4_5;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_geometry_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_source_language_glsl;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_spirv_version_1_0;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_opengl;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_tess_control_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_tess_evaluation_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.util.spvc.Spvc.SPVC_BACKEND_MSL;
import static org.lwjgl.util.spvc.Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS_TIER;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_VERSION;
import static org.lwjgl.util.spvc.Spvc.SPVC_MSL_PLATFORM_MACOS;
import static org.lwjgl.util.spvc.Spvc.SPVC_SUCCESS;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;

/**
 * Preferred in-process translator using Minecraft 26.2's LWJGL 3.4.1
 * shaderc and SPIRV-Cross bindings.
 *
 * <p>The default factory is execution-disabled. Enabling requires constructing
 * the backend with {@link ExecutionPolicy#EXPLICITLY_ENABLED}; even then it is
 * not invoked unless an external coordinator calls {@link #translate}.</p>
 */
public final class LwjglShadercSpvcBackend
    implements IrisShaderTranslatorBackend {
  public static final String BACKEND_ID = "lwjgl-shaderc-spvc-3.4.1";
  private static final String SHADERC_CLASS =
      "org.lwjgl.util.shaderc.Shaderc";
  private static final String SPVC_CLASS = "org.lwjgl.util.spvc.Spvc";
  private static final Pattern GLSL_VERSION = Pattern.compile(
      "(?m)^(\\s*#\\s*version\\s+)(\\d+)([^\\r\\n]*)");

  private final ExecutionPolicy executionPolicy;

  public LwjglShadercSpvcBackend(ExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public static LwjglShadercSpvcBackend disabledByDefault() {
    return new LwjglShadercSpvcBackend(ExecutionPolicy.DISABLED);
  }

  @Override
  public String id() {
    return BACKEND_ID;
  }

  @Override
  public IrisTranslationProfile profile() {
    return IrisTranslationProfile.LWJGL_3_4_1_METAL_3_ARGUMENT_BUFFERS;
  }

  @Override
  public StageSupport support(IrisShaderStage stage) {
    return stage == IrisShaderStage.GEOMETRY
        ? StageSupport.UNSUPPORTED_KEEP_IRIS_OPENGL
        : StageSupport.SUPPORTED;
  }

  @Override
  public Availability discover() {
    ClassLoader loader = LwjglShadercSpvcBackend.class.getClassLoader();
    try {
      Class.forName(SHADERC_CLASS, false, loader);
      Class.forName(SPVC_CLASS, false, loader);
      return new Availability(true,
          "LWJGL shaderc and spvc bindings are present; native loading deferred");
    } catch (ClassNotFoundException missing) {
      return new Availability(false,
          "Missing " + missing.getMessage() + "; backend remains unavailable");
    } catch (LinkageError incompatible) {
      return new Availability(false,
          "LWJGL bindings are incompatible: "
              + incompatible.getClass().getSimpleName());
    }
  }

  @Override
  public IrisShaderTranslation translate(IrisFinalShaderProgram program)
      throws IrisShaderTranslationException {
    if (executionPolicy != ExecutionPolicy.EXPLICITLY_ENABLED) {
      throw new IrisShaderTranslationException(
          "in-process translation is disabled; explicit opt-in is required");
    }
    if (!discover().available()) {
      throw new IrisShaderTranslationException(discover().detail());
    }

    try {
      return translateEnabled(program);
    } catch (IrisShaderTranslationException expected) {
      throw expected;
    } catch (LinkageError nativeFailure) {
      throw new IrisShaderTranslationException(
          "LWJGL translation native library is unavailable", nativeFailure);
    } catch (RuntimeException unexpected) {
      throw new IrisShaderTranslationException(
          "LWJGL translation failed", unexpected);
    }
  }

  private IrisShaderTranslation translateEnabled(IrisFinalShaderProgram program)
      throws IrisShaderTranslationException {
    if (program.hasStage(IrisShaderStage.GEOMETRY)) {
      throw new IrisShaderTranslationException(
          "Metal has no geometry shader stage; keep this program on the Iris OpenGL path");
    }
    long compiler = Shaderc.shaderc_compiler_initialize();
    if (compiler == MemoryUtil.NULL) {
      throw new IrisShaderTranslationException(
          "shaderc_compiler_initialize returned null");
    }
    long options = Shaderc.shaderc_compile_options_initialize();
    if (options == MemoryUtil.NULL) {
      Shaderc.shaderc_compiler_release(compiler);
      throw new IrisShaderTranslationException(
          "shaderc_compile_options_initialize returned null");
    }

    try {
      Shaderc.shaderc_compile_options_set_source_language(options,
          shaderc_source_language_glsl);
      Shaderc.shaderc_compile_options_set_target_env(options,
          shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
      Shaderc.shaderc_compile_options_set_target_spirv(options,
          shaderc_spirv_version_1_0);
      /*
       * Resource names are part of the GL-to-Metal binding bridge. They are
       * deliberately retained in SPIR-V so reflected resources can be matched
       * to the uniform/block names observed at the live Iris OpenGL boundary.
       * They are diagnostics/ABI metadata only and never enter the semantic
       * resource-layout identity.
       */
      Shaderc.shaderc_compile_options_set_generate_debug_info(options);
      Shaderc.shaderc_compile_options_set_optimization_level(options,
          shaderc_optimization_level_performance);
      Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
      Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);

      EnumMap<IrisShaderStage, IrisShaderTranslation.StageArtifacts> stages =
          new EnumMap<>(IrisShaderStage.class);
      for (IrisShaderStage stage : IrisShaderStage.values()) {
        String source = program.source(stage);
        if (source == null) {
          continue;
        }
        byte[] spirv = compileSpirv(compiler, options, stage, source);
        String msl = compileMsl(stage, spirv);
        stages.put(stage,
            new IrisShaderTranslation.StageArtifacts(spirv, msl));
      }
      return new IrisShaderTranslation(
          IrisShaderCacheKey.from(program, profile()), id(), profile(), stages);
    } finally {
      Shaderc.shaderc_compile_options_release(options);
      Shaderc.shaderc_compiler_release(compiler);
    }
  }

  private static byte[] compileSpirv(long compiler, long options,
      IrisShaderStage stage, String source)
      throws IrisShaderTranslationException {
    /*
     * Do not use shaderc's CharSequence overload here. LWJGL encodes that
     * overload on the thread-local MemoryStack, which is intentionally small
     * and throws OutOfMemoryError for real, post-Iris shader-pack sources.
     * The ByteBuffer overload keeps large source text in explicitly managed
     * off-heap storage instead.
     */
    ByteBuffer encodedSource = null;
    ByteBuffer encodedFileName = null;
    ByteBuffer encodedEntryPoint = null;
    long result;
    try {
      encodedSource = MemoryUtil.memUTF8(normalizeForShaderc(source), false);
      encodedFileName =
          MemoryUtil.memUTF8(stage.cacheName() + ".glsl", true);
      encodedEntryPoint = MemoryUtil.memUTF8("main", true);
      result = Shaderc.shaderc_compile_into_spv(compiler, encodedSource,
          shadercKind(stage), encodedFileName, encodedEntryPoint, options);
    } finally {
      MemoryUtil.memFree(encodedEntryPoint);
      MemoryUtil.memFree(encodedFileName);
      MemoryUtil.memFree(encodedSource);
    }
    if (result == MemoryUtil.NULL) {
      throw new IrisShaderTranslationException(
          "shaderc returned null for " + stage.cacheName());
    }
    try {
      int status = Shaderc.shaderc_result_get_compilation_status(result);
      if (status != shaderc_compilation_status_success) {
        throw new IrisShaderTranslationException(
            "shaderc failed for " + stage.cacheName() + ": "
                + Shaderc.shaderc_result_get_error_message(result));
      }
      ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
      if (bytes == null || !bytes.hasRemaining()) {
        throw new IrisShaderTranslationException(
            "shaderc produced empty SPIR-V for " + stage.cacheName());
      }
      byte[] copy = new byte[bytes.remaining()];
      bytes.get(copy);
      return copy;
    } finally {
      Shaderc.shaderc_result_release(result);
    }
  }

  /**
   * OpenGL-targeted SPIR-V requires desktop GLSL 330 or newer. Iris' own
   * center-depth helper is valid GLSL 150, so shaderc receives a version-floor
   * adaptation while the cache key remains bound to Iris' exact final source.
   */
  static String normalizeForShaderc(String source) {
    Matcher matcher = GLSL_VERSION.matcher(source);
    if (!matcher.find()) {
      return source;
    }
    int version;
    try {
      version = Integer.parseInt(matcher.group(2));
    } catch (NumberFormatException malformed) {
      return source;
    }
    if (version >= 330) {
      return source;
    }
    return source.substring(0, matcher.start(2)) + "330"
        + source.substring(matcher.end(2));
  }

  private static String compileMsl(IrisShaderStage stage, byte[] spirv)
      throws IrisShaderTranslationException {
    if (spirv.length % Integer.BYTES != 0) {
      throw new IrisShaderTranslationException(
          "unaligned SPIR-V for " + stage.cacheName());
    }

    ByteBuffer storage = MemoryUtil.memAlloc(spirv.length)
        .order(ByteOrder.nativeOrder());
    try {
      storage.put(spirv).flip();
      IntBuffer words = storage.asIntBuffer();
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer contextPointer = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_context_create(contextPointer), MemoryUtil.NULL,
            "create context");
        long context = contextPointer.get(0);
        try {
          PointerBuffer parsedIr = stack.mallocPointer(1);
          checkSpvc(Spvc.spvc_context_parse_spirv(context, words,
              words.remaining(), parsedIr), context, "parse SPIR-V");

          PointerBuffer compilerPointer = stack.mallocPointer(1);
          checkSpvc(Spvc.spvc_context_create_compiler(context,
              SPVC_BACKEND_MSL, parsedIr.get(0),
              SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPointer), context,
              "create MSL compiler");
          long compiler = compilerPointer.get(0);

          PointerBuffer optionsPointer = stack.mallocPointer(1);
          checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler,
              optionsPointer), context, "create MSL options");
          long options = optionsPointer.get(0);
          checkSpvc(Spvc.spvc_compiler_options_set_uint(options,
              SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS),
              context, "select macOS MSL");
          checkSpvc(Spvc.spvc_compiler_options_set_uint(options,
              SPVC_COMPILER_OPTION_MSL_VERSION, 30000), context,
              "select MSL 3.0");
          checkSpvc(Spvc.spvc_compiler_options_set_bool(options,
              SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, true), context,
              "enable MSL argument buffers");
          // SPIRV-Cross uses 0 for tier 1 and 1 for tier 2.
          checkSpvc(Spvc.spvc_compiler_options_set_uint(options,
              SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS_TIER, 1), context,
              "select MSL argument-buffer tier 2");
          checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler,
              options), context, "install MSL options");

          PointerBuffer sourcePointer = stack.mallocPointer(1);
          checkSpvc(Spvc.spvc_compiler_compile(compiler, sourcePointer),
              context, "compile MSL");
          String msl = MemoryUtil.memUTF8(sourcePointer.get(0));
          if (msl == null || msl.isBlank()) {
            throw new IrisShaderTranslationException(
                "SPIRV-Cross produced empty MSL for " + stage.cacheName());
          }
          return msl;
        } finally {
          Spvc.spvc_context_destroy(context);
        }
      }
    } finally {
      MemoryUtil.memFree(storage);
    }
  }

  private static void checkSpvc(int result, long context, String operation)
      throws IrisShaderTranslationException {
    if (result == SPVC_SUCCESS) {
      return;
    }
    String detail = context == MemoryUtil.NULL
        ? ""
        : Spvc.spvc_context_get_last_error_string(context);
    throw new IrisShaderTranslationException(
        "SPIRV-Cross could not " + operation + " (code " + result + ")"
            + (detail == null || detail.isBlank() ? "" : ": " + detail));
  }

  private static int shadercKind(IrisShaderStage stage) {
    return switch (stage) {
      case VERTEX -> shaderc_vertex_shader;
      case TESS_CONTROL -> shaderc_tess_control_shader;
      case TESS_EVALUATION -> shaderc_tess_evaluation_shader;
      case GEOMETRY -> shaderc_geometry_shader;
      case FRAGMENT -> shaderc_fragment_shader;
      case COMPUTE -> shaderc_compute_shader;
    };
  }

  public enum ExecutionPolicy {
    DISABLED,
    EXPLICITLY_ENABLED
  }
}
