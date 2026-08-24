package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Bounded generation-safe mirror of GL texture and sampler parameters. */
public final class IrisGlSamplerMirror {
  public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
  public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
  public static final int GL_TEXTURE_WRAP_S = 0x2802;
  public static final int GL_TEXTURE_WRAP_T = 0x2803;
  public static final int GL_TEXTURE_BORDER_COLOR = 0x1004;
  public static final int GL_TEXTURE_MIN_LOD = 0x813A;
  public static final int GL_TEXTURE_MAX_LOD = 0x813B;
  public static final int GL_TEXTURE_BASE_LEVEL = 0x813C;
  public static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
  public static final int GL_TEXTURE_WRAP_R = 0x8072;
  public static final int GL_TEXTURE_LOD_BIAS = 0x8501;
  public static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
  public static final int GL_TEXTURE_COMPARE_FUNC = 0x884D;
  public static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;
  public static final int DEFAULT_MAX_OBJECTS = 4_096;
  private static final IrisGlSamplerMirror GLOBAL =
      new IrisGlSamplerMirror(DEFAULT_MAX_OBJECTS);

  private final int maxObjects;
  private final LinkedHashMap<Integer, Entry> textures =
      new LinkedHashMap<>(16, 0.75F, true);
  private final LinkedHashMap<Integer, Entry> samplers =
      new LinkedHashMap<>(16, 0.75F, true);
  private long nextGeneration = 1;
  private long evictions;
  private long rejectedUpdates;

  public IrisGlSamplerMirror(int maxObjects) {
    if (maxObjects <= 0) {
      throw new IllegalArgumentException("invalid sampler mirror bound");
    }
    this.maxObjects = maxObjects;
  }

  public static IrisGlSamplerMirror global() {
    return GLOBAL;
  }

  public synchronized void defineTexture(int texture) {
    define(textures, texture);
  }

  public synchronized void defineSampler(int sampler) {
    define(samplers, sampler);
  }

  public synchronized void textureParameteri(int texture, int pname,
      int value) {
    parameterInt(textures, texture, pname, value);
  }

  public synchronized void textureParameterf(int texture, int pname,
      float value) {
    parameterFloat(textures, texture, pname, value);
  }

  public synchronized void textureParameteriv(int texture, int pname,
      int[] values) {
    parameterInts(textures, texture, pname, values);
  }

  public synchronized void samplerParameteri(int sampler, int pname,
      int value) {
    parameterInt(samplers, sampler, pname, value);
  }

  public synchronized void samplerParameterf(int sampler, int pname,
      float value) {
    parameterFloat(samplers, sampler, pname, value);
  }

  public synchronized void samplerParameteriv(int sampler, int pname,
      int[] values) {
    parameterInts(samplers, sampler, pname, values);
  }

  public synchronized long textureGeneration(int texture) {
    Entry entry = textures.get(texture);
    return entry == null ? 0 : entry.generation;
  }

  public synchronized long samplerGeneration(int sampler) {
    Entry entry = samplers.get(sampler);
    return entry == null ? 0 : entry.generation;
  }

  public synchronized Optional<Snapshot> textureSnapshot(int texture,
      long generation) {
    return snapshot(textures, texture, generation, true);
  }

  public synchronized Optional<Snapshot> samplerSnapshot(int sampler,
      long generation) {
    return snapshot(samplers, sampler, generation, false);
  }

  public synchronized void deleteTexture(int texture) {
    textures.remove(texture);
  }

  public synchronized void deleteSampler(int sampler) {
    samplers.remove(sampler);
  }

  public synchronized void clear() {
    textures.clear();
    samplers.clear();
  }

  public synchronized Status status() {
    return new Status(textures.size(), samplers.size(), evictions,
        rejectedUpdates);
  }

  private void define(LinkedHashMap<Integer, Entry> entries, int object) {
    if (object <= 0) {
      rejectedUpdates++;
      return;
    }
    if (entries.containsKey(object)) {
      entries.get(object);
      return;
    }
    entries.put(object, new Entry(nextGeneration++));
    while (entries.size() > maxObjects) {
      entries.remove(entries.entrySet().iterator().next().getKey());
      evictions++;
    }
  }

  private void parameterInt(LinkedHashMap<Integer, Entry> entries,
      int object, int pname, int value) {
    Entry entry = entries.get(object);
    if (entry == null) {
      rejectedUpdates++;
      return;
    }
    if (!entry.state.parameterInt(pname, value)) {
      entry.unsupported.add(pname);
    }
    entry.generation = nextGeneration++;
  }

  private void parameterFloat(LinkedHashMap<Integer, Entry> entries,
      int object, int pname, float value) {
    Entry entry = entries.get(object);
    if (entry == null || !Float.isFinite(value)) {
      rejectedUpdates++;
      return;
    }
    if (!entry.state.parameterFloat(pname, value)) {
      entry.unsupported.add(pname);
    }
    entry.generation = nextGeneration++;
  }

  private void parameterInts(LinkedHashMap<Integer, Entry> entries,
      int object, int pname, int[] values) {
    Entry entry = entries.get(object);
    if (entry == null || values == null || values.length == 0) {
      rejectedUpdates++;
      return;
    }
    if (pname == GL_TEXTURE_BORDER_COLOR && values.length >= 4) {
      entry.state.borderColor = List.of(values[0], values[1], values[2],
          values[3]);
      entry.state.integerBorderColor = true;
    } else if (!entry.state.parameterInt(pname, values[0])) {
      entry.unsupported.add(pname);
    }
    entry.generation = nextGeneration++;
  }

  private Optional<Snapshot> snapshot(Map<Integer, Entry> entries,
      int object, long generation, boolean textureOwned) {
    Entry entry = entries.get(object);
    if (entry == null || generation <= 0 || entry.generation != generation) {
      return Optional.empty();
    }
    return Optional.of(new Snapshot(object, generation, textureOwned,
        entry.state.freeze(entry.unsupported)));
  }

  public record SamplerState(int minFilter, int magFilter, int wrapS,
                             int wrapT, int wrapR, int compareMode,
                             int compareFunc, int baseLevel, int maxLevel,
                             float minLod, float maxLod, float lodBias,
                             float maxAnisotropy,
                             List<Integer> borderColor,
                             boolean integerBorderColor,
                             List<Integer> unsupportedParameters) {
    public SamplerState {
      borderColor = List.copyOf(borderColor);
      unsupportedParameters = List.copyOf(unsupportedParameters);
      if (borderColor.size() != 4 || baseLevel < 0
          || maxLevel < baseLevel) {
        throw new IllegalArgumentException("invalid sampler state");
      }
    }

    public boolean complete() {
      return unsupportedParameters.isEmpty();
    }
  }

  public record Snapshot(int object, long generation, boolean textureOwned,
                         SamplerState state) {
    public Snapshot {
      if (object <= 0 || generation <= 0) {
        throw new IllegalArgumentException("invalid sampler snapshot");
      }
      Objects.requireNonNull(state, "state");
    }
  }

  public record Status(int textures, int samplers, long evictions,
                       long rejectedUpdates) {
  }

  private static final class Entry {
    private final MutableState state = new MutableState();
    private final TreeSet<Integer> unsupported = new TreeSet<>();
    private long generation;

    private Entry(long generation) {
      this.generation = generation;
    }
  }

  private static final class MutableState {
    private int minFilter = 0x2702;
    private int magFilter = 0x2601;
    private int wrapS = 0x2901;
    private int wrapT = 0x2901;
    private int wrapR = 0x2901;
    private int compareMode;
    private int compareFunc = 0x0203;
    private int baseLevel;
    private int maxLevel = 1_000;
    private float minLod = -1_000.0F;
    private float maxLod = 1_000.0F;
    private float lodBias;
    private float maxAnisotropy = 1.0F;
    private List<Integer> borderColor = List.of(0, 0, 0, 0);
    private boolean integerBorderColor;

    private boolean parameterInt(int pname, int value) {
      return switch (pname) {
        case GL_TEXTURE_MIN_FILTER -> setInt(value, 0);
        case GL_TEXTURE_MAG_FILTER -> setInt(value, 1);
        case GL_TEXTURE_WRAP_S -> setInt(value, 2);
        case GL_TEXTURE_WRAP_T -> setInt(value, 3);
        case GL_TEXTURE_WRAP_R -> setInt(value, 4);
        case GL_TEXTURE_COMPARE_MODE -> setInt(value, 5);
        case GL_TEXTURE_COMPARE_FUNC -> setInt(value, 6);
        case GL_TEXTURE_BASE_LEVEL -> setInt(value, 7);
        case GL_TEXTURE_MAX_LEVEL -> setInt(value, 8);
        case GL_TEXTURE_MIN_LOD -> setFloat(value, 0);
        case GL_TEXTURE_MAX_LOD -> setFloat(value, 1);
        case GL_TEXTURE_LOD_BIAS -> setFloat(value, 2);
        case GL_TEXTURE_MAX_ANISOTROPY -> setFloat(value, 3);
        default -> false;
      };
    }

    private boolean parameterFloat(int pname, float value) {
      return switch (pname) {
        case GL_TEXTURE_MIN_LOD -> setFloat(value, 0);
        case GL_TEXTURE_MAX_LOD -> setFloat(value, 1);
        case GL_TEXTURE_LOD_BIAS -> setFloat(value, 2);
        case GL_TEXTURE_MAX_ANISOTROPY -> setFloat(value, 3);
        case GL_TEXTURE_MIN_FILTER, GL_TEXTURE_MAG_FILTER, GL_TEXTURE_WRAP_S,
            GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_R, GL_TEXTURE_COMPARE_MODE,
            GL_TEXTURE_COMPARE_FUNC, GL_TEXTURE_BASE_LEVEL,
            GL_TEXTURE_MAX_LEVEL -> parameterInt(pname, Math.round(value));
        default -> false;
      };
    }

    private boolean setInt(int value, int field) {
      switch (field) {
        case 0 -> minFilter = value;
        case 1 -> magFilter = value;
        case 2 -> wrapS = value;
        case 3 -> wrapT = value;
        case 4 -> wrapR = value;
        case 5 -> compareMode = value;
        case 6 -> compareFunc = value;
        case 7 -> {
          if (value < 0 || value > maxLevel) {
            return false;
          }
          baseLevel = value;
        }
        case 8 -> {
          if (value < baseLevel) {
            return false;
          }
          maxLevel = value;
        }
        default -> throw new IllegalArgumentException("invalid integer field");
      }
      return true;
    }

    private boolean setFloat(float value, int field) {
      switch (field) {
        case 0 -> minLod = value;
        case 1 -> maxLod = value;
        case 2 -> lodBias = value;
        case 3 -> maxAnisotropy = value;
        default -> throw new IllegalArgumentException("invalid float field");
      }
      return true;
    }

    private SamplerState freeze(TreeSet<Integer> unsupported) {
      return new SamplerState(minFilter, magFilter, wrapS, wrapT, wrapR,
          compareMode, compareFunc, baseLevel, maxLevel, minLod, maxLod,
          lodBias, maxAnisotropy, new ArrayList<>(borderColor),
          integerBorderColor,
          new ArrayList<>(unsupported));
    }
  }
}
