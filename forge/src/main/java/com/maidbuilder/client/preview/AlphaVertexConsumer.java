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
    public VertexConsumer vertex(double x, double y, double z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int a) {
        delegate.color((int) (red * this.red), (int) (green * this.green), (int) (blue * this.blue), (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int a) {
        delegate.defaultColor((int) (red * this.red), (int) (green * this.green), (int) (blue * this.blue), (int) (a * alpha));
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
