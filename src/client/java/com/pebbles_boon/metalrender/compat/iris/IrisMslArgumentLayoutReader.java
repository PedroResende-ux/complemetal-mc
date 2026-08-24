package com.pebbles_boon.metalrender.compat.iris;

import static org.lwjgl.util.spvc.Spvc.SPVC_BACKEND_MSL;
import static org.lwjgl.util.spvc.Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS_TIER;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_INVARIANT_FP_MATH;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_VERSION;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y;
import static org.lwjgl.util.spvc.Spvc.SPVC_MSL_PLATFORM_MACOS;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_GL_PLAIN_UNIFORM;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_SUCCESS;

import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Replays SPIRV-Cross assignment and queries its exact automatic MSL ids. */
public final class IrisMslArgumentLayoutReader {
  private static final int SPV_DECORATION_LOCATION = 30;
  private static final int SPV_DECORATION_BINDING = 33;
  private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;
  private static final List<ResourceType> RESOURCE_TYPES = List.of(
      new ResourceType(SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
          ResourceKind.UNIFORM_BUFFER),
      new ResourceType(SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
          ResourceKind.STORAGE_BUFFER),
      new ResourceType(SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
          ResourceKind.STORAGE_IMAGE),
      new ResourceType(SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
          ResourceKind.SAMPLED_IMAGE),
      new ResourceType(SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
          ResourceKind.TEXTURE),
      new ResourceType(SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
          ResourceKind.SAMPLER),
      new ResourceType(SPVC_RESOURCE_TYPE_GL_PLAIN_UNIFORM,
          ResourceKind.UNIFORM));

  private final IrisPipelineCache cache;
  private final IrisTranslationProfile profile;

  public IrisMslArgumentLayoutReader(IrisPipelineCache cache,
      IrisTranslationProfile profile) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  public Result read(IrisProgramIdentityRegistry.ResolvedProgram program) {
    Objects.requireNonNull(program, "program");
    ArrayList<IrisMslArgumentLayout.StageLayout> stages =
        new ArrayList<>(program.stages().size());
    try {
      for (IrisShaderStage stage : program.stages()) {
        IrisPipelineCache.VerifiedSpirvStage verified = cache
            .readVerifiedSpirvStage(program.shaderKey(), profile, stage)
            .orElse(null);
        if (verified == null) {
          return new Unsupported(stage.cacheName()
              + ":verified-spirv-missing");
        }
        StageResult result = reflect(stage, verified.spirv());
        if (result instanceof StageUnsupported unsupported) {
          return new Unsupported(stage.cacheName() + ':'
              + unsupported.reason());
        }
        stages.add(((StageComplete) result).layout());
      }
      return new Complete(new IrisMslArgumentLayout(stages));
    } catch (IOException error) {
      return new Unsupported("verified-spirv-io-failure");
    } catch (LinkageError error) {
      return new Unsupported("spvc-native-unavailable");
    }
  }

  static StageResult reflect(IrisShaderStage stage, byte[] spirv) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(spirv, "spirv");
    if (spirv.length == 0 || spirv.length % Integer.BYTES != 0
        || spirv.length > IrisSpirvResourceReflector.MAX_MODULE_BYTES) {
      return new StageUnsupported("invalid-spirv-size");
    }
    ByteBuffer storage = MemoryUtil.memAlloc(spirv.length)
        .order(ByteOrder.nativeOrder());
    try {
      storage.put(spirv).flip();
      IntBuffer words = storage.asIntBuffer();
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer contextPointer = stack.mallocPointer(1);
        if (Spvc.spvc_context_create(contextPointer) != SPVC_SUCCESS) {
          return new StageUnsupported("spvc-context-create");
        }
        long context = contextPointer.get(0);
        try {
          PointerBuffer parsedIr = stack.mallocPointer(1);
          String failure = check(Spvc.spvc_context_parse_spirv(context,
              words, words.remaining(), parsedIr), context,
              "parse-spirv");
          if (failure != null) {
            return new StageUnsupported(failure);
          }
          PointerBuffer compilerPointer = stack.mallocPointer(1);
          failure = check(Spvc.spvc_context_create_compiler(context,
              SPVC_BACKEND_MSL, parsedIr.get(0),
              SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPointer), context,
              "create-compiler");
          if (failure != null) {
            return new StageUnsupported(failure);
          }
          long compiler = compilerPointer.get(0);
          failure = configureCompiler(compiler, context, stack);
          if (failure != null) {
            return new StageUnsupported(failure);
          }
          PointerBuffer sourcePointer = stack.mallocPointer(1);
          failure = check(Spvc.spvc_compiler_compile(compiler,
              sourcePointer), context, "compile-msl-layout");
          if (failure != null) {
            return new StageUnsupported(failure);
          }
          if (Spvc.spvc_compiler_msl_needs_swizzle_buffer(compiler)
              || Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler)
              || Spvc.spvc_compiler_msl_needs_output_buffer(compiler)
              || Spvc.spvc_compiler_msl_needs_patch_output_buffer(compiler)
              || Spvc.spvc_compiler_msl_needs_input_threadgroup_mem(
                  compiler)) {
            return new StageUnsupported("hidden-msl-buffer-required");
          }
          return resources(stage, compiler, context, stack);
        } finally {
          Spvc.spvc_context_destroy(context);
        }
      }
    } catch (RuntimeException error) {
      return new StageUnsupported("spvc-reflection-"
          + error.getClass().getSimpleName().toLowerCase(Locale.ROOT));
    } finally {
      MemoryUtil.memFree(storage);
    }
  }

  private static String configureCompiler(long compiler, long context,
      MemoryStack stack) {
    PointerBuffer optionsPointer = stack.mallocPointer(1);
    String failure = check(Spvc.spvc_compiler_create_compiler_options(
        compiler, optionsPointer), context, "create-msl-options");
    if (failure != null) {
      return failure;
    }
    long options = optionsPointer.get(0);
    failure = check(Spvc.spvc_compiler_options_set_uint(options,
        SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS),
        context, "select-macos-msl");
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_uint(options,
          SPVC_COMPILER_OPTION_MSL_VERSION, 30000), context,
          "select-msl-version");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_bool(options,
          SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, true), context,
          "enable-argument-buffers");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_uint(options,
          SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS_TIER, 1), context,
          "select-argument-buffer-tier");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_bool(options,
          SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, true), context,
          "fixup-depth-convention");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_bool(options,
          SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true), context,
          "flip-vertex-y");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_options_set_bool(options,
          SPVC_COMPILER_OPTION_MSL_INVARIANT_FP_MATH, false), context,
          "preserve-explicit-invariant-fp-math-only");
    }
    if (failure == null) {
      failure = check(Spvc.spvc_compiler_install_compiler_options(compiler,
          options), context, "install-msl-options");
    }
    return failure;
  }

  private static StageResult resources(IrisShaderStage stage, long compiler,
      long context, MemoryStack stack) {
    PointerBuffer resourcesPointer = stack.mallocPointer(1);
    String failure = check(Spvc.spvc_compiler_create_shader_resources(
        compiler, resourcesPointer), context, "create-resources");
    if (failure != null) {
      return new StageUnsupported(failure);
    }
    ArrayList<ArgumentBinding> bindings = new ArrayList<>();
    for (ResourceType resourceType : RESOURCE_TYPES) {
      PointerBuffer listPointer = stack.mallocPointer(1);
      PointerBuffer countPointer = stack.mallocPointer(1);
      failure = check(Spvc.spvc_resources_get_resource_list_for_type(
          resourcesPointer.get(0), resourceType.spvcType(), listPointer,
          countPointer), context, "list-resources");
      if (failure != null) {
        return new StageUnsupported(failure);
      }
      long countValue = countPointer.get(0);
      if (countValue < 0 || countValue > IrisSpirvResourceLayout.MAX_RESOURCES
          || bindings.size() + countValue
          > IrisSpirvResourceLayout.MAX_RESOURCES) {
        return new StageUnsupported("resource-count-exceeded");
      }
      SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(
          listPointer.get(0), (int) countValue);
      for (int index = 0; index < reflected.capacity(); index++) {
        SpvcReflectedResource resource = reflected.get(index);
        BindingResolution resolved = binding(compiler, resource.id(),
            resourceType.kind());
        if (resolved.binding() == null) {
          return new StageUnsupported(resolved.reason());
        }
        bindings.add(resolved.binding());
      }
    }
    try {
      return new StageComplete(new IrisMslArgumentLayout.StageLayout(stage,
          bindings));
    } catch (IllegalArgumentException invalid) {
      return new StageUnsupported("resource-binding-conflict");
    }
  }

  private static BindingResolution binding(long compiler, int resourceId,
      ResourceKind kind) {
    int descriptorSet = decoration(compiler, resourceId,
        SPV_DECORATION_DESCRIPTOR_SET);
    if (descriptorSet < 0) {
      return BindingResolution.unsupported(
          kind.cacheName() + "-descriptor-set-missing");
    }
    ResourceAddress address;
    if (kind == ResourceKind.UNIFORM) {
      int location = decoration(compiler, resourceId,
          SPV_DECORATION_LOCATION);
      if (location < 0) {
        return BindingResolution.unsupported(
            "uniform-location-missing");
      }
      address = new UniformLocation(descriptorSet, location);
    } else {
      int descriptorBinding = decoration(compiler, resourceId,
          SPV_DECORATION_BINDING);
      if (descriptorBinding < 0) {
        return BindingResolution.unsupported(
            kind.cacheName() + "-descriptor-binding-missing");
      }
      address = new DescriptorAddress(descriptorSet, descriptorBinding);
    }
    int primary = Spvc.spvc_compiler_msl_get_automatic_resource_binding(
        compiler, resourceId);
    int secondary =
        Spvc.spvc_compiler_msl_get_automatic_resource_binding_secondary(
            compiler, resourceId);
    if (primary < 0) {
      return BindingResolution.unsupported(kind.cacheName()
          + "-primary-msl-id-missing");
    }
    if (kind != ResourceKind.SAMPLED_IMAGE) {
      secondary = -1;
    }
    try {
      return BindingResolution.complete(new ArgumentBinding(address, kind,
          descriptorSet, primary, secondary));
    } catch (IllegalArgumentException invalid) {
      return BindingResolution.unsupported(
          kind.cacheName() + "-argument-binding-invalid");
    }
  }

  private static int decoration(long compiler, int id, int decoration) {
    return Spvc.spvc_compiler_has_decoration(compiler, id, decoration)
        ? Spvc.spvc_compiler_get_decoration(compiler, id, decoration) : -1;
  }

  private static String check(int result, long context, String operation) {
    if (result == SPVC_SUCCESS) {
      return null;
    }
    String detail = Spvc.spvc_context_get_last_error_string(context);
    return operation + (detail == null || detail.isBlank()
        ? "" : "-failed");
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(IrisMslArgumentLayout layout) implements Result {
    public Complete {
      Objects.requireNonNull(layout, "layout");
    }
  }

  public record Unsupported(String reason) implements Result {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
    }
  }

  sealed interface StageResult permits StageComplete, StageUnsupported {
  }

  record StageComplete(IrisMslArgumentLayout.StageLayout layout)
      implements StageResult {
  }

  record StageUnsupported(String reason) implements StageResult {
  }

  private record ResourceType(int spvcType, ResourceKind kind) {
  }

  private record BindingResolution(ArgumentBinding binding, String reason) {
    private BindingResolution {
      if ((binding == null) == reason.isEmpty()) {
        throw new IllegalArgumentException(
            "binding resolution requires exactly one outcome");
      }
    }

    private static BindingResolution complete(ArgumentBinding binding) {
      return new BindingResolution(Objects.requireNonNull(binding, "binding"),
          "");
    }

    private static BindingResolution unsupported(String reason) {
      return new BindingResolution(null,
          Objects.requireNonNull(reason, "reason"));
    }
  }
}
