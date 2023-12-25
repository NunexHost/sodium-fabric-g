package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.SortBehavior;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.BSPBuildFailureException;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.TimingRecorder.Counter;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.AnyOrderData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.BSPDynamicData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.DynamicData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.NoData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.PresentTranslucentData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.StaticNormalRelativeData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.StaticTopoAcyclicData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.TopoSortDynamicData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.trigger.GeometryPlanes;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.trigger.SortTriggering;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.minecraft.util.math.ChunkSectionPos;

/**
 * The translucent geometry collector collects the data from the renderers and
 * builds data structures for either dynamic triggering or static sorting. It
 * determines the best sort type for the section and constructs various types of
 * translucent data objects that then perform sorting and get registered with
 * GFNI for triggering.
 * 
 * An instance of this class is created for each meshing task. It goes through
 * three stages:
 * 1. During meshing it collects the geometry and calculates some metrics on the
 * fly. These are later used for the sort type heuristic.
 * 2. With {@link #finishRendering()} it finishes the geometry collection,
 * generates the quad list, and calculates additional metrics. Then the sort
 * type is determined with a heuristic based on the collected metrics. This
 * determines if block face culling can be enabled.
 * - Now the {@link BuiltSectionMeshParts} is generates which yields the vertex
 * ranges.
 * 3. The vertex ranges and the mesh parts object are used by the collector in
 * the construction of the {@link TranslucentData} object. The data object
 * allocates memory for the index data and performs the first (and for static
 * sort types, only) sort.
 * - The data object is put into the {@link ChunkBuildOutput}.
 * 
 * When dynamic sorting is enabled, trigger information from {@link DynamicData}
 * object is integrated into {@link SortTriggering} when the task result is
 * received by the main thread.
 */
public class TranslucentGeometryCollector {
    private static final Logger LOGGER = LogManager.getLogger(TranslucentGeometryCollector.class);

    private final ChunkSectionPos sectionPos;

    // true if there are any unaligned quads
    private boolean hasUnaligned = false;

    // a bitmap of the aligned facings present in the section
    private int alignedFacingBitmap = 0;

    // AABB of the geometry
    private float[] extents = new float[ModelQuadFacing.DIRECTIONS];

    // true if one of the extents has more than one plane
    private boolean alignedExtentsMultiple = false;

    // the maximum (or minimum for negative directions) of quads with a particular
    // facing. (Dot product of the normal with a vertex for all aligned facings)
    private float[] alignedExtremes = new float[] {
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY
    };

    // keep track of two normals with each up to two distances for important special
    // case heuristics
    private int unalignedANormal = -1;
    private float unalignedADistance1 = Float.NaN;
    private float unalignedADistance2 = Float.NaN;
    private int unalignedBNormal = -1;
    private float unalignedBDistance1 = Float.NaN;
    private float unalignedBDistance2 = Float.NaN;

    @SuppressWarnings("unchecked")
    private ReferenceArrayList<TQuad>[] quadLists = new ReferenceArrayList[ModelQuadFacing.COUNT];
    private TQuad[] quads;

    private SortType sortType;

    private boolean quadHashPresent = false;
    private int quadHash = 0;

    public TranslucentGeometryCollector(ChunkSectionPos sectionPos) {
        this.sectionPos = sectionPos;
    }

    private static final float INV_QUANTIZE_EPSILON = 256f;
    private static final float QUANTIZE_EPSILON = 1f / INV_QUANTIZE_EPSILON;

    static {
        // ensure it fits with the fluid renderer epsilon and that it's a power-of-two
        // fraction
        var targetEpsilon = FluidRenderer.EPSILON * 2.1f;
        if (QUANTIZE_EPSILON <= targetEpsilon && Integer.bitCount((int) INV_QUANTIZE_EPSILON) == 1) {
            throw new RuntimeException("epsilon is invalid: " + QUANTIZE_EPSILON);
        }
    }

    public void appendQuad(int packedNormal, ChunkVertexEncoder.Vertex[] vertices, ModelQuadFacing facing) {
        float xSum = 0;
        float ySum = 0;
        float zSum = 0;

        // keep track of distinct vertices to compute the center accurately for
        // degenerate quads
        float lastX = vertices[3].x;
        float lastY = vertices[3].y;
        float lastZ = vertices[3].z;
        int uniqueQuads = 0;

        float posXExtent = Float.NEGATIVE_INFINITY;
        float posYExtent = Float.NEGATIVE_INFINITY;
        float posZExtent = Float.NEGATIVE_INFINITY;
        float negXExtent = Float.POSITIVE_INFINITY;
        float negYExtent = Float.POSITIVE_INFINITY;
        float negZExtent = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 4; i++) {
            float x = vertices[i].x;
            float y = vertices[i].y;
            float z = vertices[i].z;

            posXExtent = Math.max(posXExtent, x);
            posYExtent = Math.max(posYExtent, y);
            posZExtent = Math.max(posZExtent, z);
            negXExtent = Math.min(negXExtent, x);
            negYExtent = Math.min(negYExtent, y);
            negZExtent = Math.min(negZExtent, z);

            if (x != lastX || y != lastY || z != lastZ) {
                xSum += x;
                ySum += y;
                zSum += z;
                uniqueQuads++;
            }
            if (i != 3) {
                lastX = x;
                lastY = y;
                lastZ = z;
            }
        }

        // shrink quad in non-normal directions to prevent intersections caused by
        // epsilon offsets applied by FluidRenderer
        if (facing != ModelQuadFacing.POS_X && facing != ModelQuadFacing.NEG_X) {
            posXExtent -= QUANTIZE_EPSILON;
            negXExtent += QUANTIZE_EPSILON;
            if (negXExtent > posXExtent) {
                negXExtent = posXExtent;
            }
        }
        if (facing != ModelQuadFacing.POS_Y && facing != ModelQuadFacing.NEG_Y) {
            posYExtent -= QUANTIZE_EPSILON;
            negYExtent += QUANTIZE_EPSILON;
            if (negYExtent > posYExtent) {
                negYExtent = posYExtent;
            }
        }
        if (facing != ModelQuadFacing.POS_Z && facing != ModelQuadFacing.NEG_Z) {
            posZExtent -= QUANTIZE_EPSILON;
            negZExtent += QUANTIZE_EPSILON;
            if (negZExtent > posZExtent) {
                negZExtent = posZExtent;
            }
        }

        // POS_X, POS_Y, POS_Z, NEG_X, NEG_Y, NEG_Z
        float[] extents = new float[] { posXExtent, posYExtent, posZExtent, negXExtent, negYExtent, negZExtent };

        int direction = facing.ordinal();
        var quadList = this.quadLists[direction];
        if (quadList == null) {
            quadList = new ReferenceArrayList<>();
            this.quadLists[direction] = quadList;
        } else if (facing.isAligned()) {
            this.alignedExtentsMultiple = true;
        }

        if (facing.isAligned()) {
            // only update global extents if there are no unaligned quads since this is only
            // used for the convex box test which doesn't work with unaligned quads anyways
            if (!this.hasUnaligned) {
                this.extents[0] = Math.max(this.extents[0], posXExtent);
                this.extents[1] = Math.max(this.extents[1], posYExtent);
                this.extents[2] = Math.max(this.extents[2], posZExtent);
                this.extents[3] = Math.min(this.extents[3], negXExtent);
                this.extents[4] = Math.min(this.extents[4], negYExtent);
                this.extents[5] = Math.min(this.extents[5], negZExtent);
            }

            var quad = TQuad.fromAligned(facing, extents);
            quadList.add(quad);

            var extreme = this.alignedExtremes[direction];
            var distance = quad.getDotProduct();
            if (facing.getSign() > 1) {
                this.alignedExtremes[direction] = Math.max(extreme, distance);
            } else {
                this.alignedExtremes[direction] = Math.min(extreme, distance);
            }
        } else {
            this.hasUnaligned = true;

            var centerX = xSum / uniqueQuads;
            var centerY = ySum / uniqueQuads;
            var centerZ = zSum / uniqueQuads;
            var center = new Vector3f(centerX, centerY, centerZ);

            var quad = TQuad.fromUnaligned(facing, extents, center, packedNormal);
            quadList.add(quad);

            // update the two unaligned normals that are tracked
            var distance = quad.getDotProduct();
            if (packedNormal == this.unalignedANormal) {
                if (Float.isNaN(this.unalignedADistance1)) {
                    this.unalignedADistance1 = distance;
                } else {
                    this.unalignedADistance2 = distance;
                }
            } else if (packedNormal == this.unalignedBNormal) {
                if (Float.isNaN(this.unalignedBDistance1)) {
                    this.unalignedBDistance1 = distance;
                } else {
                    this.unalignedBDistance2 = distance;
                }
            } else if (this.unalignedANormal == -1) {
                this.unalignedANormal = packedNormal;
                this.unalignedADistance1 = distance;
            } else if (this.unalignedBNormal == -1) {
                this.unalignedBNormal = packedNormal;
                this.unalignedBDistance1 = distance;
            }
        }
    }

    /**
     * Filters the given sort type to fit within the selected sorting mode. If it
     * doesn't match, then it's set to the NONE sort type.
     * 
     * @param sortType the sort type to filter
     */
    private static SortType filterSortType(SortType sortType) {
        SortBehavior sortBehavior = SodiumClientMod.options().performance.sortBehavior;
        switch (sortBehavior) {
            case OFF:
                return SortType.NONE;
            case STATIC:
                if (sortType == SortType.STATIC_NORMAL_RELATIVE || sortType == SortType.STATIC_TOPO) {
                    return sortType;
                } else {
                    return SortType.NONE;
                }
            case DYNAMIC:
                return sortType;
            default:
                throw new IllegalStateException("Unknown sort behavior: " + sortBehavior);
        }
    }

    /**
     * Array of how many quads a section can have with a given number of unique
     * normals so that a static topo sort is attempted on it. -1 means the value is
     * unused and doesn't make sense to give.
     */
    private static int[] STATIC_TOPO_SORT_ATTEMPT_LIMITS = new int[] { -1, -1, 250, 100, 50, 30 };

    /**
     * Determines the sort type for the collected geometry from the section. It
     * determines a sort type, which is either no sorting, a static sort or a
     * dynamic sort (section in GFNI only in this case).
     * 
     * See the section on special cases for an explanation of the special sorting
     * cases: https://hackmd.io/@douira100/sodium-sl-gfni#Special-Sorting-Cases
     * 
     * A: If there are no or only one normal, this builder can be considered
     * practically empty.
     * 
     * B: If there are two face planes with opposing normals at the same distance,
     * then
     * they can't be seen through each other and this section can be ignored.
     * 
     * C: If the translucent faces are on the surface of the convex hull of all
     * translucent faces in the section and face outwards, then there is no way to
     * see one through another. Since convex hulls are hard, a simpler case only
     * uses the axis aligned normals: Under the condition that only aligned normals
     * are used in the section, tracking the bounding box of the translucent
     * geometry (the vertices) in the section and then checking if the normal
     * distances line up with the bounding box allows the exclusion of some
     * sections containing a single convex translucent cuboid (of which not all
     * faces need to exist).
     * 
     * D: If there are only two normals which are opposites of
     * each other, then a special fixed sort order is always a correct sort order.
     * This ordering sorts the two sets of face planes by their ascending
     * normal-relative distance. The ordering between the two normals is irrelevant
     * as they can't be seen through each other anyways.
     * 
     * More heuristics can be performed here to conservatively determine if this
     * section could possibly have more than one translucent sort order.
     * 
     * @return the required sort type to ensure this section always looks correct
     */
    private SortType sortTypeHeuristic() {
        SortBehavior sortBehavior = SodiumClientMod.options().performance.sortBehavior;
        int alignedNormalCount = Integer.bitCount(this.alignedFacingBitmap);
        int alignedPlaneCount = alignedNormalCount;
        if (this.alignedExtentsMultiple) {
            alignedPlaneCount = 100;
        }

        int unalignedPlaneCount = 0;
        if (!Float.isNaN(this.unalignedADistance1)) {
            unalignedPlaneCount++;
        }
        if (!Float.isNaN(this.unalignedADistance2)) {
            unalignedPlaneCount++;
        }
        if (!Float.isNaN(this.unalignedBDistance1)) {
            unalignedPlaneCount++;
        }
        if (!Float.isNaN(this.unalignedBDistance2)) {
            unalignedPlaneCount++;
        }

        int planeCount = alignedPlaneCount + unalignedPlaneCount;

        int unalignedNormalCount = 0;
        if (unalignedANormal != -1) {
            unalignedNormalCount++;
        }
        if (unalignedBNormal != -1) {
            unalignedNormalCount++;
        }

        int normalCount = alignedNormalCount + unalignedNormalCount;

        // special case A
        if (sortBehavior == SortBehavior.OFF || planeCount <= 1) {
            return SortType.NONE;
        }

        if (!this.hasUnaligned) {
            boolean twoOpposingNormals = this.alignedFacingBitmap == ModelQuadFacing.OPPOSING_X
                    || this.alignedFacingBitmap == ModelQuadFacing.OPPOSING_Y
                    || this.alignedFacingBitmap == ModelQuadFacing.OPPOSING_Z;

            // special case B
            // if there are just two normals, they are exact opposites of eachother and they
            // each only have one distance, there is no way to see through one face to the
            // other.
            if (planeCount == 2 && twoOpposingNormals) {
                return SortType.NONE;
            }

            // special case C
            // the more complex test that checks for distances aligned with the bounding box
            if (!this.alignedExtentsMultiple) {
                boolean passesBoundingBoxTest = true;
                for (int direction = 0; direction < ModelQuadFacing.DIRECTIONS; direction++) {
                    var extreme = this.alignedExtremes[direction];
                    if (Float.isInfinite(extreme)) {
                        continue;
                    }

                    // check the distance against the bounding box
                    var sign = direction < 3 ? 1 : -1;
                    if (sign * extreme != this.extents[direction]) {
                        passesBoundingBoxTest = false;
                        break;
                    }
                }
                if (passesBoundingBoxTest) {
                    Counter.HEURISTIC_BOUNDING_BOX.increment();
                    return SortType.NONE;
                }
            }

            // special case D
            // there are up to two normals that are opposing, this means no dynamic sorting
            // is necessary. Without static sorting, the geometry to trigger on could be
            // reduced but this isn't done here as we assume static sorting is possible.
            if (twoOpposingNormals || alignedNormalCount == 1) {
                return SortType.STATIC_NORMAL_RELATIVE;
            }
        } else if (alignedNormalCount == 0) {
            // special case D but for one normal or two opposing unaligned normals
            if (unalignedNormalCount == 1
                    || unalignedNormalCount == 2 && NormI8.isOpposite(this.unalignedANormal, this.unalignedBNormal)) {
                Counter.HEURISTIC_OPPOSING_UNALIGNED.increment();
                return SortType.STATIC_NORMAL_RELATIVE;
            }
        } else if (planeCount == 2) { // implies normalCount == 2
            // special case D with mixed aligned and unaligned normals
            int alignedDirection = Integer.numberOfTrailingZeros(this.alignedFacingBitmap);
            if (NormI8.isOpposite(this.unalignedANormal, ModelQuadFacing.PACKED_ALIGNED_NORMALS[alignedDirection])) {
                Counter.HEURISTIC_OPPOSING_UNALIGNED.increment();
                return SortType.STATIC_NORMAL_RELATIVE;
            }
        }

        // use the given set of quad count limits to determine if a static topo sort
        // should be attempted

        var attemptLimitIndex = Math.max(Math.min(normalCount, STATIC_TOPO_SORT_ATTEMPT_LIMITS.length - 1), 2);
        if (this.quads.length <= STATIC_TOPO_SORT_ATTEMPT_LIMITS[attemptLimitIndex]) {
            return SortType.STATIC_TOPO;
        }

        return SortType.DYNAMIC;
    }

    public SortType finishRendering() {
        // combine the quads into one array
        int totalQuadCount = 0;
        for (var quadList : this.quadLists) {
            if (quadList != null) {
                totalQuadCount += quadList.size();
            }
        }
        this.quads = new TQuad[totalQuadCount];
        int quadIndex = 0;
        for (int direction = 0; direction < ModelQuadFacing.COUNT; direction++) {
            var quadList = this.quadLists[direction];
            if (quadList != null) {
                for (var quad : quadList) {
                    this.quads[quadIndex++] = quad;
                }
                if (direction < ModelQuadFacing.DIRECTIONS) {
                    this.alignedFacingBitmap |= 1 << direction;
                }
            }
        }
        this.quadLists = null; // not needed anymore

        this.sortType = filterSortType(sortTypeHeuristic());
        return this.sortType;
    }

    private TranslucentData makeNewTranslucentData(BuiltSectionMeshParts translucentMesh, Vector3fc cameraPos,
            TranslucentData oldData) {
        if (this.sortType == SortType.NONE) {
            return AnyOrderData.fromMesh(translucentMesh, this.quads, this.sectionPos, null);
        }

        if (this.sortType == SortType.STATIC_NORMAL_RELATIVE) {
            var isDoubleUnaligned = this.alignedFacingBitmap == 0;
            return StaticNormalRelativeData.fromMesh(translucentMesh, this.quads, this.sectionPos, isDoubleUnaligned);
        }

        // from this point on we know the estimated sort type requires direction mixing
        // (no backface culling) and all vertices are in the UNASSIGNED direction.
        NativeBuffer buffer = PresentTranslucentData.nativeBufferForQuads(this.quads);
        if (this.sortType == SortType.STATIC_TOPO) {
            var result = StaticTopoAcyclicData.fromMesh(translucentMesh, this.quads, this.sectionPos, buffer);
            if (result != null) {
                return result;
            }
            this.sortType = SortType.DYNAMIC;
        }

        // filter the sort type with the user setting and re-evaluate
        this.sortType = filterSortType(this.sortType);

        if (this.sortType == SortType.NONE) {
            return AnyOrderData.fromMesh(translucentMesh, this.quads, this.sectionPos, buffer);
        }

        if (this.sortType == SortType.DYNAMIC) {
            if (!SortTriggering.DEBUG_ONLY_TOPO_OR_DISTANCE_SORT) {
                try {
                    return BSPDynamicData.fromMesh(
                        translucentMesh, cameraPos, this.quads, this.sectionPos,
                        buffer, oldData);
                } catch (BSPBuildFailureException e) {
                    // TODO: investigate existing BSP build failures, then remove this logging
                    LOGGER.warn(
                        "BSP build failure at {}. Please report this to douira for evaluation alongside with some way of reproducing the geometry in this section. (coordinates and world file or seed)",
                        sectionPos);
                    var geometryPlanes = GeometryPlanes.fromQuadLists(sectionPos, this.quads);
                    return TopoSortDynamicData.fromMesh(
                        translucentMesh, cameraPos, this.quads, this.sectionPos,
                        geometryPlanes, buffer);
                    }
            } else {
                var geometryPlanes = GeometryPlanes.fromQuadLists(sectionPos, this.quads);
                return TopoSortDynamicData.fromMesh(
                        translucentMesh, cameraPos, this.quads, this.sectionPos,
                        geometryPlanes, buffer);
            }
        }

        throw new IllegalStateException("Unknown sort type: " + this.sortType);
    }

    private int getQuadHash(TQuad[] quads) {
        if (this.quadHashPresent) {
            return this.quadHash;
        }

        for (int i = 0; i < quads.length; i++) {
            var quad = quads[i];
            this.quadHash = this.quadHash * 31 + quad.getQuadHash() + i * 3;
        }
        return this.quadHash;
    }

    public TranslucentData getTranslucentData(
            TranslucentData oldData, BuiltSectionMeshParts translucentMesh, Vector3fc cameraPos) {
        // means there is no translucent geometry
        if (translucentMesh == null) {
            return new NoData(sectionPos);
        }

        // re-use the original translucent data if it's the same. This reduces the
        // amount of generated and uploaded index data when sections are rebuilt without
        // relevant changes to translucent geometry. Rebuilds happen when any part of
        // the section changes, including the here irrelevant cases of changes to opaque
        // geometry or light levels.
        if (oldData != null) {
            // for the NONE sort type the ranges need to be the same, the actual geometry
            // doesn't matter
            if (this.sortType == SortType.NONE && oldData instanceof AnyOrderData oldAnyData
                    && oldAnyData.getLength() == this.quads.length
                    && Arrays.equals(oldAnyData.getVertexRanges(), translucentMesh.getVertexRanges())) {
                oldAnyData.setReuseUploadedData();
                return oldAnyData;
            }

            // for the other sort types the geometry needs to be the same (checked with
            // length and hash)
            if (oldData instanceof PresentTranslucentData oldPresentData) {
                if (oldPresentData.getLength() == this.quads.length
                        && oldPresentData.getQuadHash() == getQuadHash(this.quads)) {
                    oldPresentData.setReuseUploadedData();
                    return oldPresentData;
                }
            }
        }

        var newData = makeNewTranslucentData(translucentMesh, cameraPos, oldData);
        if (newData instanceof PresentTranslucentData presentData) {
            presentData.setQuadHash(getQuadHash(this.quads));
        }
        return newData;
    }
}
