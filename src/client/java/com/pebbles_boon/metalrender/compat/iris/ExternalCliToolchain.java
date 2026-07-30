package com.pebbles_boon.metalrender.compat.iris;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Side-effect-free discovery for an optional CLI fallback.
 *
 * <p>Discovery only checks executable files. It never launches
 * {@code glslangValidator} or {@code spirv-cross}; the in-process LWJGL backend
 * is preferred.</p>
 */
public record ExternalCliToolchain(Optional<Path> glslangValidator,
                                   Optional<Path> spirvCross) {
  private static final List<Path> COMMON_BIN_DIRS = List.of(
      Path.of("/opt/homebrew/bin"),
      Path.of("/usr/local/bin"),
      Path.of("/usr/bin"));

  public static ExternalCliToolchain discover() {
    return discover(System.getenv("PATH"), true);
  }

  static ExternalCliToolchain discover(String searchPath,
      boolean includeCommonLocations) {
    List<Path> directories = new ArrayList<>();
    if (searchPath != null && !searchPath.isBlank()) {
      for (String entry : searchPath.split(
          java.util.regex.Pattern.quote(
              System.getProperty("path.separator")))) {
        if (!entry.isBlank()) {
          directories.add(Path.of(entry));
        }
      }
    }
    if (includeCommonLocations) {
      for (Path common : COMMON_BIN_DIRS) {
        if (!directories.contains(common)) {
          directories.add(common);
        }
      }
    }
    return new ExternalCliToolchain(
        findExecutable(directories, "glslangValidator"),
        findExecutable(directories, "spirv-cross"));
  }

  public boolean complete() {
    return glslangValidator.isPresent() && spirvCross.isPresent();
  }

  private static Optional<Path> findExecutable(List<Path> directories,
      String name) {
    for (Path directory : directories) {
      Path candidate = directory.resolve(name).toAbsolutePath().normalize();
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }
}
