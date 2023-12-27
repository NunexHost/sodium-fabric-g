package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.floats.FloatArrays;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;

/**
 * A normal vector that has additional information about its alignment. This is
 * useful for better hashing and telling other code that the normal is aligned,
 * which in turn enables many optimizations and fast paths to be taken.
 */
public class AlignableNormal extends Vector3f {
    private static final AlignableNormal[] NORMALS;

    static {
        NORMALS = new AlignableNormal[ModelQuadFacing.DIRECTIONS];
        for (int i = 0; i < ModelQuadFacing.DIRECTIONS; i++) {
            NORMALS[i] = new AlignableNormal(ModelQuadFacing.ALIGNED_NORMALS[i], i);
        }
    }

    private static final int UNASSIGNED = ModelQuadFacing.UNASSIGNED.ordinal();
    private final int alignedDirection;

    private AlignableNormal(Vector3fc v, int alignedDirection) {
        super(v);
        this.alignedDirection = alignedDirection;
    }

    public static AlignableNormal fromAligned(int alignedDirection) {
        return NORMALS[alignedDirection];
    }

    public static AlignableNormal fromUnaligned(Vector3fc v) {
        return new AlignableNormal(v, UNASSIGNED);
    }

    public int getAlignedDirection() {
        return this.alignedDirection;
    }

    public boolean isAligned() {
        return this.alignedDirection != UNASSIGNED;
    }

    @Override
    public int hashCode() {
        if (this.isAligned()) {
            return this.alignedDirection;
        } else {
            return super.hashCode() + ModelQuadFacing.DIRECTIONS;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        AlignableNormal other = (AlignableNormal) obj;
        if (alignedDirection != other.alignedDirection)
            return false;
        return true;
    }

    /**
     * This method is optimized by using a `TreeSet` to perform the query. This is
     * a more efficient data structure for range queries than a binary search,
     * especially when the data is static or frequently updated.
     *
     * @param sortedDistances The sorted array of distances to query.
     * @param start The start of the query range.
     * @param end The end of the query range.
     * @return True if there is an entry in the query range, false otherwise.
     */
    public static boolean queryRange(float[] sortedDistances, float start, float end) {
        // Create a `TreeSet` from the sorted distances.
        TreeSet<Float> distances = new TreeSet<>();
        for (float distance : sortedDistances) {
            distances.add(distance);
        }

        // Check if the query range intersects the set.
        return distances.subSet(start, true, end, true).size() > 0;
    
