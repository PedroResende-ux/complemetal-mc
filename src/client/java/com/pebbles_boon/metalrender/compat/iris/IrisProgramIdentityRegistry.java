package com.pebbles_boon.metalrender.compat.iris;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Associates short-lived OpenGL program names with content-addressed shader
 * identities without hashing on Iris' render/link thread.
 *
 * <p>A registration object is generation-specific. Deleting and reusing the
 * same OpenGL integer can therefore never attach an old shader key to a new
 * program. The background translation worker resolves the key after it polls
 * the bounded source queue.</p>
 */
public final class IrisProgramIdentityRegistry {
  public static final int DEFAULT_CAPACITY = 512;
  private static final byte[] PASS_DOMAIN =
      "metalrender.iris.pass-identity.v1"
          .getBytes(StandardCharsets.US_ASCII);
  private static final IrisProgramIdentityRegistry GLOBAL =
      new IrisProgramIdentityRegistry(DEFAULT_CAPACITY);

  private final int capacity;
  private final AtomicLong nextGeneration = new AtomicLong();
  private final Map<Integer, Registration> byGlProgram = new HashMap<>();
  private final ArrayDeque<Registration> insertionOrder = new ArrayDeque<>();
  private long evictions;
  private long deleted;

  public IrisProgramIdentityRegistry(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  public static IrisProgramIdentityRegistry global() {
    return GLOBAL;
  }

  /** Fast render-thread registration. No source digest is computed here. */
  public synchronized Registration register(int glProgram,
      ProgramDescriptor descriptor) {
    if (glProgram <= 0) {
      throw new IllegalArgumentException("OpenGL program must be positive");
    }
    Objects.requireNonNull(descriptor, "descriptor");
    Registration prior = byGlProgram.remove(glProgram);
    if (prior != null) {
      prior.markDeleted();
      insertionOrder.remove(prior);
    }
    Registration registration = new Registration(
        nextGeneration.incrementAndGet(), glProgram, descriptor);
    byGlProgram.put(glProgram, registration);
    insertionOrder.addLast(registration);
    while (byGlProgram.size() > capacity) {
      Registration eldest = insertionOrder.removeFirst();
      if (byGlProgram.remove(eldest.glProgram(), eldest)) {
        eldest.markDeleted();
        evictions++;
      }
    }
    return registration;
  }

  /** Called on the background worker after the final-GLSL key is known. */
  public ResolvedProgram resolve(Registration registration,
      IrisShaderCacheKey shaderKey, java.util.Collection<IrisShaderStage>
          stages) {
    Objects.requireNonNull(registration, "registration");
    Objects.requireNonNull(shaderKey, "shaderKey");
    ResolvedProgram resolved = new ResolvedProgram(registration.generation(),
        registration.glProgram(), shaderKey,
        new IrisPipelineState.PassIdentity(
            registration.descriptor().kind(),
            passIdentitySha256(registration.descriptor()),
            registration.descriptor().fallback()),
        registration.descriptor(), normalizedStages(stages));
    registration.resolve(resolved);
    return resolved;
  }

  private static List<IrisShaderStage> normalizedStages(
      java.util.Collection<IrisShaderStage> stages) {
    java.util.EnumSet<IrisShaderStage> unique =
        java.util.EnumSet.noneOf(IrisShaderStage.class);
    unique.addAll(Objects.requireNonNull(stages, "stages"));
    return List.copyOf(unique);
  }

  public synchronized Optional<Registration> lookup(int glProgram) {
    return Optional.ofNullable(byGlProgram.get(glProgram));
  }

  public synchronized void delete(int glProgram) {
    Registration registration = byGlProgram.remove(glProgram);
    if (registration != null) {
      insertionOrder.remove(registration);
      registration.markDeleted();
      deleted++;
    }
  }

  public synchronized void reset() {
    for (Registration registration : insertionOrder) {
      registration.markDeleted();
    }
    byGlProgram.clear();
    insertionOrder.clear();
  }

  public synchronized int size() {
    return byGlProgram.size();
  }

  public synchronized long evictions() {
    return evictions;
  }

  public synchronized long deletedPrograms() {
    return deleted;
  }

  static String passIdentitySha256(ProgramDescriptor descriptor) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
      DataOutputStream output = new DataOutputStream(bytes);
      putString(output, descriptor.kind().cacheName());
      putString(output, descriptor.programName());
      output.writeBoolean(descriptor.fallback());
      output.writeInt(descriptor.vertexBuffers().size());
      for (IrisPipelineState.VertexBufferLayout buffer
          : descriptor.vertexBuffers()) {
        output.writeInt(buffer.bufferIndex());
        output.writeInt(buffer.strideBytes());
        putString(output, buffer.stepFunction().cacheName());
        output.writeInt(buffer.stepRate());
      }
      output.writeInt(descriptor.vertexAttributes().size());
      for (IrisPipelineState.VertexAttribute attribute
          : descriptor.vertexAttributes()) {
        output.writeInt(attribute.location());
        output.writeInt(attribute.bufferIndex());
        output.writeInt(attribute.offsetBytes());
        putString(output, attribute.format().cacheName());
      }
      output.flush();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(PASS_DOMAIN);
      digest.update(bytes.toByteArray());
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("cannot encode Iris pass identity",
          impossible);
    }
  }

  private static void putString(DataOutputStream output, String value)
      throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  public record ProgramDescriptor(IrisPipelineState.PassKind kind,
                                  String programName, boolean fallback,
                                  List<IrisPipelineState.VertexBufferLayout>
                                      vertexBuffers,
                                  List<IrisPipelineState.VertexAttribute>
                                      vertexAttributes) {
    public ProgramDescriptor {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(programName, "programName");
      if (programName.isBlank() || programName.length() > 1024) {
        throw new IllegalArgumentException("invalid Iris program name");
      }
      vertexBuffers = List.copyOf(vertexBuffers);
      vertexAttributes = List.copyOf(vertexAttributes);
      if (kind == IrisPipelineState.PassKind.COMPUTE
          && (!vertexBuffers.isEmpty() || !vertexAttributes.isEmpty())) {
        throw new IllegalArgumentException(
            "compute descriptor cannot contain vertex state");
      }
      if (kind != IrisPipelineState.PassKind.COMPUTE
          && vertexBuffers.isEmpty()) {
        throw new IllegalArgumentException(
            "graphics descriptor requires vertex state");
      }
    }
  }

  public static final class Registration {
    private final long generation;
    private final int glProgram;
    private final ProgramDescriptor descriptor;
    private volatile ResolvedProgram resolved;
    private volatile boolean deleted;

    private Registration(long generation, int glProgram,
        ProgramDescriptor descriptor) {
      this.generation = generation;
      this.glProgram = glProgram;
      this.descriptor = descriptor;
    }

    public long generation() {
      return generation;
    }

    public int glProgram() {
      return glProgram;
    }

    public ProgramDescriptor descriptor() {
      return descriptor;
    }

    public Optional<ResolvedProgram> resolved() {
      return Optional.ofNullable(resolved);
    }

    public boolean deleted() {
      return deleted;
    }

    private synchronized void resolve(ResolvedProgram value) {
      if (resolved != null && !resolved.shaderKey().equals(value.shaderKey())) {
        throw new IllegalStateException(
            "capture generation resolved to two shader keys");
      }
      resolved = value;
    }

    private void markDeleted() {
      deleted = true;
    }
  }

  public record ResolvedProgram(long generation, int glProgram,
                                IrisShaderCacheKey shaderKey,
                                IrisPipelineState.PassIdentity pass,
                                ProgramDescriptor descriptor,
                                List<IrisShaderStage> stages) {
    public ResolvedProgram {
      Objects.requireNonNull(shaderKey, "shaderKey");
      Objects.requireNonNull(pass, "pass");
      Objects.requireNonNull(descriptor, "descriptor");
      stages = List.copyOf(stages);
      if (stages.isEmpty()) {
        throw new IllegalArgumentException(
            "resolved program requires at least one shader stage");
      }
    }
  }
}
