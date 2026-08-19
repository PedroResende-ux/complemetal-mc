package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.ImageUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValue;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValueKind;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded render-thread shadow of the GL resource binding tables. */
public final class IrisGlResourceBindingTracker {
  public static final int GL_TEXTURE0 = 0x84C0;
  public static final int GL_TEXTURE_2D = 0x0DE1;
  public static final int GL_UNIFORM_BUFFER = 0x8A11;
  public static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;
  private static final int MAX_PROGRAMS = 4_096;
  private static final int MAX_LOCATIONS_PER_PROGRAM = 8_192;
  private static final int MAX_UNITS = 256;
  private static final IrisGlResourceBindingTracker GLOBAL =
      new IrisGlResourceBindingTracker();

  private final LinkedHashMap<Integer, ProgramBindings> programs =
      new LinkedHashMap<>(16, 0.75F, true);
  private final Map<Integer, MutableTextureUnit> textureUnits =
      new HashMap<>();
  private final Map<Integer, TextureBufferBinding> textureBuffers =
      new HashMap<>();
  private final Map<Integer, ImageUnitBinding> imageUnits = new HashMap<>();
  private final Map<IndexedBufferBinding, BufferBinding> indexedBuffers =
      new HashMap<>();
  private int currentProgram;
  private int activeTextureUnit;

  public static IrisGlResourceBindingTracker global() {
    return GLOBAL;
  }

  public synchronized void initializeOpenGlDefaults() {
    currentProgram = 0;
    activeTextureUnit = 0;
    textureUnits.clear();
    textureBuffers.clear();
    imageUnits.clear();
    indexedBuffers.clear();
  }

  public synchronized void registerProgram(int program) {
    if (program <= 0) {
      return;
    }
    programs.computeIfAbsent(program, ignored -> new ProgramBindings());
    trimPrograms();
  }

  public synchronized void deleteProgram(int program) {
    programs.remove(program);
    if (currentProgram == program) {
      currentProgram = 0;
    }
  }

  public synchronized void useProgram(int program) {
    currentProgram = program;
  }

  public synchronized void uniformLocation(int program, CharSequence name,
                                           int location) {
    if (program <= 0 || location < 0 || name == null) {
      return;
    }
    String copied = name.toString();
    if (copied.isBlank() || copied.length() > 4_096) {
      return;
    }
    ProgramBindings bindings = programs.computeIfAbsent(program,
        ignored -> new ProgramBindings());
    if (bindings.uniformLocations.size() >= MAX_LOCATIONS_PER_PROGRAM
        && !bindings.uniformLocations.containsKey(copied)) {
      bindings.complete = false;
      return;
    }
    bindings.uniformLocations.put(copied, location);
    bindings.uniformValues.putIfAbsent(location,
        UniformValue.defaultZero());
    trimPrograms();
  }

  public synchronized void uniformBlockIndex(int program, String name,
                                             int index) {
    if (program <= 0 || index < 0 || name == null || name.isBlank()) {
      return;
    }
    ProgramBindings bindings = programs.computeIfAbsent(program,
        ignored -> new ProgramBindings());
    bindings.uniformBlockIndices.put(name, index);
    trimPrograms();
  }

  public synchronized void uniformBlockBinding(int program, int blockIndex,
                                               int binding) {
    if (program <= 0 || blockIndex < 0 || binding < 0) {
      return;
    }
    programs.computeIfAbsent(program, ignored -> new ProgramBindings())
        .uniformBlockBindings.put(blockIndex, binding);
    trimPrograms();
  }

  public synchronized void uniformInts(int location, int... values) {
    if (location < 0 || values == null || values.length == 0
        || values.length > 4) {
      return;
    }
    long[] raw = new long[values.length];
    for (int index = 0; index < values.length; index++) {
      raw[index] = Integer.toUnsignedLong(values[index]);
    }
    uniformValue(location, new UniformValue(UniformValueKind.SIGNED_INT,
        values.length, 1, raw));
  }

  public synchronized void uniformUInts(int location, int... values) {
    if (location < 0 || values == null || values.length == 0
        || values.length > 4) {
      return;
    }
    long[] raw = new long[values.length];
    for (int index = 0; index < values.length; index++) {
      raw[index] = Integer.toUnsignedLong(values[index]);
    }
    uniformValue(location, new UniformValue(UniformValueKind.UNSIGNED_INT,
        values.length, 1, raw));
  }

  public synchronized void uniformFloats(int location, float... values) {
    if (location < 0 || values == null || values.length == 0
        || values.length > 4) {
      return;
    }
    long[] raw = new long[values.length];
    for (int index = 0; index < values.length; index++) {
      raw[index] = Integer.toUnsignedLong(
          Float.floatToRawIntBits(values[index]));
    }
    uniformValue(location, new UniformValue(UniformValueKind.FLOAT,
        values.length, 1, raw));
  }

  public synchronized void uniformMatrix(int location, int size,
                                         FloatBuffer values) {
    if (values == null) {
      return;
    }
    FloatBuffer copy = values.duplicate();
    int count = Math.multiplyExact(size, size);
    if (copy.remaining() < count) {
      return;
    }
    float[] raw = new float[count];
    copy.get(raw);
    uniformMatrix(location, size, raw);
  }

  public synchronized void uniformMatrix(int location, int size,
                                         float[] values) {
    if (location < 0 || (size != 3 && size != 4) || values == null
        || values.length < size * size) {
      return;
    }
    long[] raw = new long[size * size];
    for (int index = 0; index < raw.length; index++) {
      raw[index] = Integer.toUnsignedLong(
          Float.floatToRawIntBits(values[index]));
    }
    uniformValue(location, new UniformValue(UniformValueKind.FLOAT,
        size, size, raw));
  }

  private void uniformValue(int location, UniformValue value) {
    ProgramBindings bindings = programs.get(currentProgram);
    if (bindings != null) {
      bindings.uniformValues.put(location, value);
    }
  }

  public synchronized void activeTexture(int glTexture) {
    int unit = glTexture - GL_TEXTURE0;
    if (unit >= 0 && unit < MAX_UNITS) {
      activeTextureUnit = unit;
    }
  }

  public synchronized void bindTexture(int texture) {
    bindTextureToUnit(GL_TEXTURE_2D, activeTextureUnit, texture);
  }

  public synchronized void bindTexture(int target, int texture) {
    bindTextureToUnit(target, activeTextureUnit, texture);
  }

  public synchronized void bindTextureToUnit(int target, int unit,
                                             int texture) {
    if (!validUnit(unit) || target < 0 || texture < 0) {
      return;
    }
    MutableTextureUnit binding = textureUnits.computeIfAbsent(unit,
        ignored -> new MutableTextureUnit());
    binding.target = target;
    binding.texture = texture;
  }

  public synchronized void bindSamplerToUnit(int unit, int sampler) {
    if (!validUnit(unit) || sampler < 0) {
      return;
    }
    textureUnits.computeIfAbsent(unit, ignored -> new MutableTextureUnit())
        .sampler = sampler;
  }

  public synchronized TextureUnitBinding activeTextureBinding() {
    MutableTextureUnit binding = textureUnits.get(activeTextureUnit);
    return binding == null ? null : binding.snapshot();
  }

  public synchronized void texBuffer(int target, int internalFormat,
                                     int buffer) {
    MutableTextureUnit unit = textureUnits.get(activeTextureUnit);
    if (unit == null || unit.texture <= 0 || target < 0
        || internalFormat < 0 || buffer < 0) {
      return;
    }
    textureBuffers.put(unit.texture,
        new TextureBufferBinding(target, internalFormat, buffer));
  }

  public synchronized void bindImageTexture(int unit, int texture, int level,
      boolean layered, int layer, int access, int format) {
    if (!validUnit(unit) || texture < 0 || level < 0 || layer < 0) {
      return;
    }
    imageUnits.put(unit, new ImageUnitBinding(texture, level, layered, layer,
        access, format));
  }

  public synchronized void bindBufferBase(int target, int index, int buffer) {
    if (index < 0 || index >= MAX_UNITS || buffer < 0) {
      return;
    }
    indexedBuffers.put(new IndexedBufferBinding(target, index),
        BufferBinding.base(buffer));
  }

  public synchronized void bindBufferRange(int target, int index, int buffer,
      long offsetBytes, long sizeBytes) {
    if (index < 0 || index >= MAX_UNITS || buffer < 0 || offsetBytes < 0
        || sizeBytes < 0 || (buffer > 0 && sizeBytes == 0)) {
      return;
    }
    indexedBuffers.put(new IndexedBufferBinding(target, index),
        BufferBinding.range(buffer, offsetBytes, sizeBytes));
  }

  public synchronized IrisGlResourceBindingSnapshot snapshot() {
    ProgramBindings bindings = programs.get(currentProgram);
    if (currentProgram <= 0 || bindings == null || !bindings.complete) {
      return null;
    }
    Map<Integer, TextureUnitBinding> textures = new HashMap<>();
    for (Map.Entry<Integer, MutableTextureUnit> entry
        : textureUnits.entrySet()) {
      textures.put(entry.getKey(), entry.getValue().snapshot());
    }
    return new IrisGlResourceBindingSnapshot(currentProgram,
        bindings.uniformLocations, bindings.uniformValues,
        bindings.uniformBlockIndices, bindings.uniformBlockBindings,
        textures, textureBuffers, imageUnits, indexedBuffers);
  }

  private static boolean validUnit(int unit) {
    return unit >= 0 && unit < MAX_UNITS;
  }

  private void trimPrograms() {
    while (programs.size() > MAX_PROGRAMS) {
      programs.remove(programs.entrySet().iterator().next().getKey());
    }
  }

  private static final class ProgramBindings {
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private final Map<Integer, UniformValue> uniformValues = new HashMap<>();
    private final Map<String, Integer> uniformBlockIndices = new HashMap<>();
    private final Map<Integer, Integer> uniformBlockBindings = new HashMap<>();
    private boolean complete = true;
  }

  private static final class MutableTextureUnit {
    private int target;
    private int texture;
    private int sampler;

    private TextureUnitBinding snapshot() {
      return new TextureUnitBinding(target, texture, sampler);
    }
  }
}
