package com.pebbles_boon.metalrender.compat.iris;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic identity of a GL-name-free Iris render graph. */
public record IrisRenderGraphKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.render-graph.v1".getBytes(StandardCharsets.US_ASCII);

  public IrisRenderGraphKey {
    IrisRenderGraph.requireSha(sha256, "render graph key");
  }

  public static IrisRenderGraphKey from(IrisRenderGraph graph) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(graph.resources().size());
      for (IrisRenderGraph.Resource resource : graph.resources()) {
        output.writeInt(resource.id());
        put(output, resource.kind().name());
        put(output, resource.format());
        output.writeInt(resource.sampleCount());
      }
      output.writeInt(graph.nodes().size());
      for (IrisRenderGraph.Node node : graph.nodes()) {
        output.writeInt(node.id());
        put(output, node.kind().name());
        put(output, node.phase().name());
        put(output, node.shaderKeySha256());
        put(output, node.pipelineKeySha256());
        output.writeInt(node.barrierBits());
        output.writeInt(node.resources().size());
        for (IrisRenderGraph.ResourceUse use : node.resources()) {
          output.writeInt(use.resourceId());
          put(output, use.access().name());
        }
      }
      output.writeInt(graph.edges().size());
      for (IrisRenderGraph.Edge edge : graph.edges()) {
        output.writeInt(edge.fromNode());
        output.writeInt(edge.toNode());
      }
      output.flush();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(DOMAIN);
      digest.update(bytes.toByteArray());
      return new IrisRenderGraphKey(
          HexFormat.of().formatHex(digest.digest()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("cannot encode render graph", impossible);
    }
  }

  private static void put(DataOutputStream output, String value)
      throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
