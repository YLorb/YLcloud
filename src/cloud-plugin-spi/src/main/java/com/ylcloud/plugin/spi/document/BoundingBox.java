package com.ylcloud.plugin.spi.document;

/**
 * Axis-aligned rectangle in normalized rendered-page coordinates: top-left
 * origin, x rightward, y downward, all values in [0, 1]. Providers convert pixel
 * or point coordinates using the same page orientation as the reported result.
 */
public record BoundingBox(double left, double top, double right, double bottom) {
    public BoundingBox {
        if (!Double.isFinite(left) || !Double.isFinite(top)
                || !Double.isFinite(right) || !Double.isFinite(bottom)
                || left < 0 || top < 0 || right > 1 || bottom > 1
                || left >= right || top >= bottom) {
            throw new IllegalArgumentException("invalid normalized bounding box");
        }
    }
}
