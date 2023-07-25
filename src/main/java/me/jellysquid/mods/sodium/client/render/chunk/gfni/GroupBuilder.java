package me.jellysquid.mods.sodium.client.render.chunk.gfni;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.ints.Int2ReferenceLinkedOpenHashMap;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.util.math.ChunkSectionPos;

public class GroupBuilder {
    public static final Vector3fc[] ALIGNED_NORMALS = new Vector3fc[ModelQuadFacing.DIRECTIONS.length];

    static {
        for (int i = 0; i < ModelQuadFacing.DIRECTIONS.length; i++) {
            ALIGNED_NORMALS[i] = new Vector3f(ModelQuadFacing.DIRECTIONS[i].toDirection().getUnitVector());
        }
    }

    AccumulationGroup[] axisAlignedDistances;
    Int2ReferenceLinkedOpenHashMap<AccumulationGroup> unalignedDistances;

    final ChunkSectionPos sectionPos;
    private int facePlaneCount = 0;
    private int alignedNormalBitmap = 0;
    private Vector3f minBounds = new Vector3f(16, 16, 16);
    private Vector3f maxBounds = new Vector3f(0, 0, 0);

    public GroupBuilder(ChunkSectionPos sectionPos) {
        this.sectionPos = sectionPos;
    }

    public void addAlignedFace(ModelQuadFacing facing, float vertexX, float vertexY, float vertexZ) {
        if (facing == ModelQuadFacing.UNASSIGNED) {
            throw new IllegalArgumentException("Cannot add an unaligned face with addAlignedFace()");
        }

        if (this.axisAlignedDistances == null) {
            this.axisAlignedDistances = new AccumulationGroup[ModelQuadFacing.DIRECTIONS.length];
        }

        int index = facing.ordinal();
        AccumulationGroup distances = this.axisAlignedDistances[index];

        if (distances == null) {
            distances = new AccumulationGroup(sectionPos, ALIGNED_NORMALS[index], index);
            this.axisAlignedDistances[index] = distances;
            this.alignedNormalBitmap |= 1 << index;
        }

        addVertex(distances, vertexX, vertexY, vertexZ);
    }

    public void updateAlignedBounds(float vertexX, float vertexY, float vertexZ) {
        if (this.unalignedDistances == null) {
            minBounds.x = Math.min(minBounds.x, vertexX);
            minBounds.y = Math.min(minBounds.y, vertexY);
            minBounds.z = Math.min(minBounds.z, vertexZ);

            maxBounds.x = Math.max(maxBounds.x, vertexX);
            maxBounds.y = Math.max(maxBounds.y, vertexY);
            maxBounds.z = Math.max(maxBounds.z, vertexZ);
        }
    }

    public void addUnalignedFace(int normalX, int normalY, int normalZ,
            float vertexX, float vertexY, float vertexZ) {
        if (this.unalignedDistances == null) {
            this.unalignedDistances = new Int2ReferenceLinkedOpenHashMap<>(4);
        }

        // the key for the hash map is the normal packed into an int
        // the lowest byte is 0xFF to prevent collisions with axis-aligned normals
        // (assuming quantization with 32, which is 5 bits per component)
        int normalKey = 0xFF | (normalX & 0xFF << 8) | (normalY & 0xFF << 15) | (normalZ & 0xFF << 22);
        AccumulationGroup distances = this.unalignedDistances.get(normalKey);

        if (distances == null) {
            // actually normalize the vector to ensure it's a unit vector
            // for the rest of the process which requires that
            Vector3f normal = new Vector3f(normalX, normalY, normalZ);
            normal.normalize();
            distances = new AccumulationGroup(sectionPos, normal, normalKey);
            this.unalignedDistances.put(normalKey, distances);
        }

        addVertex(distances, vertexX, vertexY, vertexZ);
    }

    private void addVertex(AccumulationGroup accGroup, float vertexX, float vertexY, float vertexZ) {
        if (accGroup.add(vertexX, vertexY, vertexZ)) {
            this.facePlaneCount++;
        }
    }

    AccumulationGroup getGroupForNormal(NormalList normalList) {
        int groupBuilderKey = normalList.getGroupBuilderKey();
        if (groupBuilderKey < 0xFF) {
            if (this.axisAlignedDistances == null) {
                return null;
            }
            return this.axisAlignedDistances[groupBuilderKey];
        } else {
            if (this.unalignedDistances == null) {
                return null;
            }
            return this.unalignedDistances.get(groupBuilderKey);
        }
    }

    /**
     * Checks if this group builder is relevant for translucency sort triggering.
     * 
     * If there are no or only one normal, this builder can be considered
     * practically empty.
     * 
     * If there are two face planes with opposing normals at the same distance, then
     * they can't be seen through each other and this section can be ignored.
     * 
     * If the translucent faces are on the surface of the convex hull of all
     * translucent faces in the section and face outwards, then there is no way to
     * see one through the other. Since convex nulls are hard, a simpler case only
     * uses the axis aligned normals: Under the condition that only aligned normals
     * are used in the section, tracking the bounding box of the translucent
     * geometry (the vertices) in the section and then checking if the normal
     * distances line up with the bounding box allows the exclusion of some
     * sections containing a single convex translucent cuboid (of which not all
     * faces need to exist).
     * 
     * More heuristics can be performed here to conservatively determine if this
     * section could possibly have more than one translucent sort order.
     * 
     * @return true if this group builder is relevant
     */
    boolean isRelevant() {
        if (facePlaneCount <= 1) {
            return false;
        }

        if (this.unalignedDistances == null) {
            // if there are just two normals, they are exact opposites of eachother and they
            // each only have one distance, there is no way to see through one face to the
            // other.
            if (this.facePlaneCount == 2
                    && (this.alignedNormalBitmap == 0b11
                            || this.alignedNormalBitmap == 0b1100
                            || this.alignedNormalBitmap == 0b110000)) {
                return false;
            }

            // the more complex test that checks for distances aligned with the bounding box
            // of the geometry added to the group builder
            if (this.facePlaneCount <= ModelQuadFacing.DIRECTIONS.length) {
                boolean passesBoundingBoxTest = true;
                for (AccumulationGroup accGroup : this.axisAlignedDistances) {
                    if (accGroup == null) {
                        continue;
                    }

                    if (accGroup.relativeDistances.size() > 1) {
                        passesBoundingBoxTest = false;
                        break;
                    }

                    // check the distance against the bounding box
                    float outwardBoundDistance = (accGroup.normal.x() < 0
                            || accGroup.normal.y() < 0
                            || accGroup.normal.z() < 0)
                                    ? accGroup.normal.dot(minBounds)
                                    : accGroup.normal.dot(maxBounds);
                    if (accGroup.relativeDistances.iterator().nextDouble() != outwardBoundDistance) {
                        passesBoundingBoxTest = false;
                        break;
                    }
                }
                if (passesBoundingBoxTest) {
                    return false;
                }
            }
        }

        return true;
    }
}
