package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.NodeKind;
import java.util.Objects;

/**
 * Exact transient arguments for a captured Iris transfer operation.
 *
 * <p>These values deliberately stay out of the content-keyed graph topology.
 * They retain generation-qualified GL identities only inside the one-frame
 * execution plan and therefore cannot accidentally address a reused name.</p>
 */
public sealed interface IrisTransferCommand permits
    IrisTransferCommand.BlitFramebuffer,
    IrisTransferCommand.CopyTexImage2D,
    IrisTransferCommand.CopyTexSubImage2D,
    IrisTransferCommand.GenerateMipmaps,
    IrisTransferCommand.Unknown {
  int GL_COLOR_BUFFER_BIT = 0x00004000;
  int GL_DEPTH_BUFFER_BIT = 0x00000100;
  int GL_STENCIL_BUFFER_BIT = 0x00000400;
  int GL_NEAREST = 0x2600;
  int GL_LINEAR = 0x2601;

  NodeKind kind();

  default boolean complete() {
    return !(this instanceof Unknown);
  }

  record BlitFramebuffer(ResourceHandle sourceFramebuffer,
                         ResourceHandle destinationFramebuffer,
                         int sourceX0, int sourceY0,
                         int sourceX1, int sourceY1,
                         int destinationX0, int destinationY0,
                         int destinationX1, int destinationY1,
                         int mask, int filter)
      implements IrisTransferCommand {
    public BlitFramebuffer {
      requireKind(sourceFramebuffer, ResourceKind.FRAMEBUFFER, "source");
      requireKind(destinationFramebuffer, ResourceKind.FRAMEBUFFER,
          "destination");
      int supportedMask = GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
          | GL_STENCIL_BUFFER_BIT;
      if (mask == 0 || (mask & ~supportedMask) != 0
          || filter != GL_NEAREST && filter != GL_LINEAR
          || filter == GL_LINEAR
              && (mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) != 0) {
        throw new IllegalArgumentException("invalid framebuffer blit");
      }
    }

    @Override
    public NodeKind kind() {
      return NodeKind.BLIT;
    }
  }

  record CopyTexImage2D(ResourceHandle sourceFramebuffer,
                        ResourceHandle sourceTexture,
                        ResourceHandle destinationTexture,
                        int target, int level, int internalFormat,
                        int sourceX, int sourceY, int width, int height,
                        int border) implements IrisTransferCommand {
    public CopyTexImage2D {
      requireKind(sourceFramebuffer, ResourceKind.FRAMEBUFFER, "source");
      requireKind(sourceTexture, ResourceKind.TEXTURE, "source texture");
      requireKind(destinationTexture, ResourceKind.TEXTURE, "destination");
      if (target <= 0 || level < 0 || internalFormat <= 0 || width <= 0
          || height <= 0 || border != 0) {
        throw new IllegalArgumentException("invalid copy texture image");
      }
    }

    @Override
    public NodeKind kind() {
      return NodeKind.COPY_TEXTURE;
    }
  }

  record CopyTexSubImage2D(ResourceHandle sourceFramebuffer,
                           ResourceHandle sourceTexture,
                           ResourceHandle destinationTexture,
                           int target, int level,
                           int destinationX, int destinationY,
                           int sourceX, int sourceY,
                           int width, int height)
      implements IrisTransferCommand {
    public CopyTexSubImage2D {
      requireKind(sourceFramebuffer, ResourceKind.FRAMEBUFFER, "source");
      requireKind(sourceTexture, ResourceKind.TEXTURE, "source texture");
      requireKind(destinationTexture, ResourceKind.TEXTURE, "destination");
      if (target <= 0 || level < 0 || destinationX < 0 || destinationY < 0
          || width <= 0 || height <= 0) {
        throw new IllegalArgumentException("invalid copy texture subimage");
      }
    }

    @Override
    public NodeKind kind() {
      return NodeKind.COPY_TEXTURE;
    }
  }

  record GenerateMipmaps(ResourceHandle texture, int target)
      implements IrisTransferCommand {
    public GenerateMipmaps {
      requireKind(texture, ResourceKind.TEXTURE, "texture");
      if (target <= 0) {
        throw new IllegalArgumentException("invalid mipmap target");
      }
    }

    @Override
    public NodeKind kind() {
      return NodeKind.GENERATE_MIPMAPS;
    }
  }

  /** Compatibility-only marker; never eligible for native graph execution. */
  record Unknown(NodeKind kind) implements IrisTransferCommand {
    public Unknown {
      Objects.requireNonNull(kind, "kind");
      if (!kind.transfer()) {
        throw new IllegalArgumentException("unknown command is not transfer");
      }
    }
  }

  private static void requireKind(ResourceHandle handle, ResourceKind kind,
      String label) {
    Objects.requireNonNull(handle, label);
    if (handle.kind() != kind) {
      throw new IllegalArgumentException(label + " has wrong resource kind");
    }
  }
}
