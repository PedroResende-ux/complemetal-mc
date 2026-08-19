package com.pebbles_boon.metalrender.compat.iris;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Reflects only integrity-checked SPIR-V belonging to a resolved program. */
public final class IrisProgramResourceLayoutReader {
  private final IrisPipelineCache cache;
  private final IrisTranslationProfile profile;

  public IrisProgramResourceLayoutReader(IrisPipelineCache cache,
      IrisTranslationProfile profile) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  public Result read(IrisProgramIdentityRegistry.ResolvedProgram program) {
    Objects.requireNonNull(program, "program");
    ArrayList<IrisProgramResourceLayout.StageLayout> stages =
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
        IrisSpirvResourceReflector.ReflectionResult reflected =
            IrisSpirvResourceReflector.reflect(verified.spirv());
        if (!reflected.successful()) {
          return new Unsupported(stage.cacheName() + ":"
              + reflected.status().name().toLowerCase(Locale.ROOT) + ":"
              + reflected.failureCode().name().toLowerCase(Locale.ROOT));
        }
        stages.add(new IrisProgramResourceLayout.StageLayout(stage,
            reflected.layout().orElseThrow()));
      }
    } catch (IOException error) {
      return new Unsupported("verified-spirv-io-failure");
    }
    return new Complete(new IrisProgramResourceLayout(stages));
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(IrisProgramResourceLayout layout) implements Result {
    public Complete {
      Objects.requireNonNull(layout, "layout");
    }
  }

  public record Unsupported(String reason) implements Result {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }
}
