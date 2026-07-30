package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IrisTranslatorDiscoveryTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void lwjglBackendIsDiscoverableWithoutLoadingNativesOrExecuting() {
    LwjglShadercSpvcBackend backend =
        LwjglShadercSpvcBackend.disabledByDefault();

    assertEquals(LwjglShadercSpvcBackend.BACKEND_ID, backend.id());
    assertTrue(backend.discover().available(), backend.discover().detail());
    assertEquals(
        IrisShaderTranslatorBackend.StageSupport.UNSUPPORTED_KEEP_IRIS_OPENGL,
        backend.support(IrisShaderStage.GEOMETRY));
    IrisShaderTranslationException error = assertThrows(
        IrisShaderTranslationException.class,
        () -> backend.translate(IrisFinalShaderProgram.fromGraphicsLink(
            "program", "void main(){}", null, null, null,
            "void main(){}")));
    assertTrue(error.getMessage().contains("explicit opt-in"));
  }

  @Test
  void externalFallbackDiscoveryDoesNotExecuteTools() throws Exception {
    Path bin = temporaryDirectory.resolve("bin");
    Files.createDirectories(bin);
    Path glslang = Files.writeString(bin.resolve("glslangValidator"), "");
    Path spirvCross = Files.writeString(bin.resolve("spirv-cross"), "");
    assertTrue(glslang.toFile().setExecutable(true));
    assertTrue(spirvCross.toFile().setExecutable(true));

    ExternalCliToolchain tools =
        ExternalCliToolchain.discover(bin.toString(), false);

    assertTrue(tools.complete());
    assertEquals(glslang.toAbsolutePath().normalize(),
        tools.glslangValidator().orElseThrow());
    assertEquals(spirvCross.toAbsolutePath().normalize(),
        tools.spirvCross().orElseThrow());
  }
}
