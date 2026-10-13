/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.model;

import net.minecraft.class_2350;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Exact vanilla rotation arithmetic for the finite set of standard JSON model rotations. */
public final class ModelRotationCache {
    // These are the float-rounded angles used by BakedQuadFactory's pinned 1.20.4 bytecode.
    private static final float MIN_SCALE = 1.0F / (float) Math.cos(0.39269909262657166D) - 1.0F;
    private static final float MAX_SCALE = 1.0F / (float) Math.cos(0.7853981852531433D) - 1.0F;
    private static final float[] ANGLES = {0.0F, -0.0F, 22.5F, -22.5F, 45.0F, -45.0F};
    private static final Rotation[][][] ROTATIONS = createRotations();

    private ModelRotationCache() { }

    /** Keep NaN propagation and overflowing custom coordinates on the untouched vanilla path. */
    public static boolean supports(Vector3f vertex, Vector3f origin) {
        return bounded(vertex) && bounded(origin);
    }

    private static boolean bounded(Vector3f vector) {
        // This is far beyond JSON model coordinates, while leaving ample headroom for every
        // subtraction, rotated component and rescale product to remain finite.
        return vector != null && Math.abs(vector.x) <= 0x1.0p100F
            && Math.abs(vector.y) <= 0x1.0p100F && Math.abs(vector.z) <= 0x1.0p100F;
    }

    /** Nonstandard rotations retain the original method, including its exceptional behavior. */
    public static Rotation find(class_2350.class_2351 axis, float angle, boolean rescale) {
        final int axisIndex;
        if (axis == class_2350.class_2351.field_11048) axisIndex = 0;
        else if (axis == class_2350.class_2351.field_11052) axisIndex = 1;
        else if (axis == class_2350.class_2351.field_11051) axisIndex = 2;
        else return null;
        // Preserve -0 rather than folding it into +0; never cache NaN or other custom angles.
        int bits = Float.floatToRawIntBits(angle);
        final int angleIndex;
        if (bits == 0) angleIndex = 0;
        else if (bits == 0x80000000) angleIndex = 1;
        else if (bits == 0x41b40000) angleIndex = 2;
        else if (bits == 0xc1b40000) angleIndex = 3;
        else if (bits == 0x42340000) angleIndex = 4;
        else if (bits == 0xc2340000) angleIndex = 5;
        else return null;
        return ROTATIONS[axisIndex][angleIndex][rescale ? 1 : 0];
    }

    private static Rotation[][][] createRotations() {
        Rotation[][][] rotations = new Rotation[3][ANGLES.length][2];
        for (int axis = 0; axis < 3; axis++) {
            for (int angle = 0; angle < ANGLES.length; angle++) {
                for (int rescale = 0; rescale < 2; rescale++) {
                    Vector3f direction = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
                    Vector3f scale = new Vector3f(axis == 0 ? 0 : 1, axis == 1 ? 0 : 1, axis == 2 ? 0 : 1);
                    Quaternionf quaternion = new Quaternionf().rotationAxis(ANGLES[angle] * 0.017453292F, direction);
                    if (rescale != 0) {
                        scale.mul(Math.abs(ANGLES[angle]) == 22.5F ? MIN_SCALE : MAX_SCALE);
                        scale.add(1.0F, 1.0F, 1.0F);
                    } else {
                        scale.set(1.0F, 1.0F, 1.0F);
                    }
                    rotations[axis][angle][rescale] = new Rotation(new Matrix4f().rotation(quaternion), scale);
                }
            }
        }
        return rotations;
    }

    /** Cached mutable JOML objects never escape, even to the original transform method or another hook. */
    public static final class Rotation {
        private final Matrix4f matrix;
        private final Vector3f scale;

        private Rotation(Matrix4f matrix, Vector3f scale) {
            this.matrix = matrix;
            this.scale = scale;
        }

        public Matrix4f copyMatrix() { return new Matrix4f(matrix); }
        public Vector3f copyScale() { return new Vector3f(scale); }
    }
}
