package com.pebbles_boon.metalrender.compat.iris;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds explicit Stage 3 function-constant state from verified SPIR-V. */
public final class IrisSpecializationStateReader {
  private final IrisPipelineCache translationCache;
  private final IrisTranslationProfile profile;

  public IrisSpecializationStateReader(IrisPipelineCache translationCache,
      IrisTranslationProfile profile) {
    this.translationCache = Objects.requireNonNull(
        translationCache, "translationCache");
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  public Result read(IrisProgramIdentityRegistry.ResolvedProgram program) {
    Objects.requireNonNull(program, "program");
    ArrayList<IrisPipelineState.FunctionConstant> constants =
        new ArrayList<>();
    try {
      for (IrisShaderStage stage : program.stages()) {
        IrisPipelineCache.VerifiedSpirvStage verified = translationCache
            .readVerifiedSpirvStage(program.shaderKey(), profile, stage)
            .orElse(null);
        if (verified == null) {
          return new Unsupported(stage.cacheName()
              + ":verified-spirv-missing");
        }
        SpirvSpecializationScanner.ScanResult result =
            SpirvSpecializationScanner.scan(verified.spirv());
        if (!(result instanceof SpirvSpecializationScanner.Scanned scanned)) {
          SpirvSpecializationScanner.Malformed malformed =
              (SpirvSpecializationScanner.Malformed) result;
          return new Unsupported(stage.cacheName() + ":spirv-"
              + malformed.failureCode().name().toLowerCase(
                  java.util.Locale.ROOT));
        }
        if (!scanned.allDecoratedSpecIdsRepresentable()) {
          return new Unsupported(stage.cacheName()
              + ":unrepresentable-specialization-constant");
        }
        for (SpirvSpecializationScanner.SpecializationConstant value
            : scanned.constants()) {
          if (value.specId().isEmpty()) {
            continue;
          }
          if (!value.decoratedSpecIdRepresentable()
              || value.rawDefaultBits().isEmpty()
              || value.specId().orElseThrow()
                  > Integer.MAX_VALUE) {
            return new Unsupported(stage.cacheName()
                + ":unsupported-specialization-constant");
          }
          IrisPipelineState.ScalarType type = scalarType(value.scalarType());
          if (type == null) {
            return new Unsupported(stage.cacheName()
                + ":unsupported-specialization-type");
          }
          constants.add(new IrisPipelineState.FunctionConstant(stage,
              (int) value.specId().orElseThrow(), type,
              value.rawDefaultBits().orElseThrow()));
          if (constants.size()
              > IrisPipelineState.MAX_FUNCTION_CONSTANTS) {
            return new Unsupported("function-constant-capacity-exceeded");
          }
        }
      }
    } catch (IOException error) {
      return new Unsupported("verified-spirv-io-failure");
    }
    constants.sort(Comparator.comparing(
            IrisPipelineState.FunctionConstant::stage)
        .thenComparingInt(
            IrisPipelineState.FunctionConstant::constantId));
    return new Complete(constants);
  }

  private static IrisPipelineState.ScalarType scalarType(
      SpirvSpecializationScanner.ScalarType type) {
    return switch (type) {
      case BOOL -> IrisPipelineState.ScalarType.BOOL;
      case INT32 -> IrisPipelineState.ScalarType.INT32;
      case UINT32 -> IrisPipelineState.ScalarType.UINT32;
      case FLOAT32 -> IrisPipelineState.ScalarType.FLOAT32;
      case INT64 -> IrisPipelineState.ScalarType.INT64;
      case UINT64 -> IrisPipelineState.ScalarType.UINT64;
      case FLOAT64 -> IrisPipelineState.ScalarType.FLOAT64;
      case UNSUPPORTED_SCALAR, COMPOSITE -> null;
    };
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(List<IrisPipelineState.FunctionConstant> constants)
      implements Result {
    public Complete {
      constants = List.copyOf(constants);
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
