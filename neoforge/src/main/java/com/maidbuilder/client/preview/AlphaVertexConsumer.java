package com.maidbuilder.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Passes vertices through, scaling their alpha so solid block models render see-through (and optionally tinting them). */
final class AlphaVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float alpha;
    private final float red, green, blue;

    AlphaVertexConsumer(VertexConsumer delegate, float alpha) {
        this(delegate, alpha, 1f, 1f, 1f);
    }

    AlphaVertexConsumer(VertexConsumer delegate, float alpha, float red, float green, float blue) {
        this.delegate = delegate;
        this.alpha = alpha;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int a) {
        delegate.setColor((int) (red * this.red), (int) (green * this.green), (int) (blue * this.blue), (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
