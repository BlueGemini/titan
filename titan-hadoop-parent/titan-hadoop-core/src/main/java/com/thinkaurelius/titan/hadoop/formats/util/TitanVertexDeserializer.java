package com.thinkaurelius.titan.hadoop.formats.util;

import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.diskstorage.Entry;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.graphdb.database.RelationReader;
import com.thinkaurelius.titan.graphdb.idmanagement.IDManager;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.relations.RelationCache;
import com.thinkaurelius.titan.graphdb.types.TypeInspector;
import com.thinkaurelius.titan.hadoop.formats.util.input.SystemTypeInspector;
import com.thinkaurelius.titan.hadoop.formats.util.input.TitanHadoopSetup;
import com.tinkerpop.gremlin.process.T;
import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.tinkergraph.structure.TinkerEdge;
import com.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import com.tinkerpop.gremlin.tinkergraph.structure.TinkerVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

public class TitanVertexDeserializer {

    private final TitanHadoopSetup setup;
    private final TypeInspector typeManager;
    private final SystemTypeInspector systemTypes;
    private final IDManager idManager;
    private final boolean verifyVertexExistence = false;

    private static final Logger log =
            LoggerFactory.getLogger(TitanVertexDeserializer.class);

    public TitanVertexDeserializer(final TitanHadoopSetup setup) {
        this.setup = setup;
        this.typeManager = setup.getTypeInspector();
        this.systemTypes = setup.getSystemTypeInspector();
        this.idManager = setup.getIDManager();
    }

    // Read a single row from the edgestore and create a TinkerVertex corresponding to the row
    // The neighboring vertices are represented by DetachedVertex instances
    public TinkerVertex readHadoopVertex(final StaticBuffer key, Iterable<Entry> entries) {

        // Convert key to a vertex ID
        final long vertexId = idManager.getKeyID(key);
        Preconditions.checkArgument(vertexId > 0);

        // Partitioned vertex handling
        if (idManager.isPartitionedVertex(vertexId)) {
            Preconditions.checkState(setup.getFilterPartitionedVertices(),
                    "Read partitioned vertex (ID=%s), but partitioned vertex filtering is disabled.", vertexId);
            log.debug("Skipping partitioned vertex with ID {}", vertexId);
            return null;
        }

        // Create TinkerVertex
        TinkerGraph tg = TinkerGraph.open();

        boolean foundVertexState = !verifyVertexExistence;

        TinkerVertex tv = null;

        // Iterate over edgestore columns to find the vertex's label relation
        for (final Entry data : entries) {
            RelationReader relationReader = setup.getRelationReader(vertexId);
            final RelationCache relation = relationReader.parseRelation(data, false, typeManager);
            if (systemTypes.isVertexLabelSystemType(relation.typeId)) {
                // Found vertex Label
                long vertexLabelId = relation.getOtherVertexId();
                VertexLabel vl = typeManager.getExistingVertexLabel(vertexLabelId);
                // Create TinkerVertex with this label
                //tv = (TinkerVertex)tg.addVertex(T.label, vl.label(), T.id, vertexId);
                tv = getOrCreateVertex(vertexId, vl.name(), tg);
            }
        }

        // Added this following testing
        if (null == tv) {
            //tv = (TinkerVertex)tg.addVertex(T.id, vertexId);
            tv = getOrCreateVertex(vertexId, null, tg);
        }

        Preconditions.checkState(null != tv, "Unable to determine vertex label for vertex with ID %s", vertexId);

        // Iterate over and decode edgestore columns (relations) on this vertex
        for (final Entry data : entries) {
            try {
                RelationReader relationReader = setup.getRelationReader(vertexId);
                final RelationCache relation = relationReader.parseRelation(data, false, typeManager);
                if (systemTypes.isVertexExistsSystemType(relation.typeId)) {
                    foundVertexState = true;
                }

                if (systemTypes.isSystemType(relation.typeId)) continue; //Ignore system types
                final RelationType type = typeManager.getExistingRelationType(relation.typeId);
                if (((InternalRelationType)type).isInvisibleType()) continue; //Ignore hidden types

                // Decode and create the relation (edge or property)
                if (type.isPropertyKey()) {
                    // Decode property
                    Object value = relation.getValue();
                    Preconditions.checkNotNull(value);
                    tv.property(type.name(), value, T.id, relation.relationId);
                } else {
                    assert type.isEdgeLabel();

                    // Partitioned vertex handling
                    if (idManager.isPartitionedVertex(relation.getOtherVertexId())) {
                        Preconditions.checkState(setup.getFilterPartitionedVertices(),
                                "Read edge incident on a partitioned vertex, but partitioned vertex filtering is disabled.  " +
                                "Relation ID: %s.  This vertex ID: %s.  Other vertex ID: %s.  Edge label: %s.",
                                relation.relationId, vertexId, relation.getOtherVertexId(), type.name());
                        log.debug("Skipping edge with ID {} incident on partitioned vertex with ID {} (and nonpartitioned vertex with ID {})",
                                relation.relationId, relation.getOtherVertexId(), vertexId);
                        continue;
                    }

                    // Decode edge
                    TinkerEdge te;

                    if (relation.direction.equals(Direction.IN)) {
                        // We don't know the label of the other vertex, but one must be provided
                        TinkerVertex outV = getOrCreateVertex(relation.getOtherVertexId(), null, tg);
                        te = (TinkerEdge)outV.addEdge(type.name(), tv, T.id, relation.relationId);
                    } else if (relation.direction.equals(Direction.OUT)) {
                        // We don't know the label of the other vertex, but one must be provided
                        TinkerVertex inV = getOrCreateVertex(relation.getOtherVertexId(), null, tg);
                        te = (TinkerEdge)tv.addEdge(type.name(), inV, T.id, relation.relationId);
                    } else {
                        throw new RuntimeException("Direction.BOTH is not supported");
                    }

                    if (relation.hasProperties()) {
                        // Load relation properties
                        for (final LongObjectCursor<Object> next : relation) {
                            assert next.value != null;
                            RelationType rt = typeManager.getExistingRelationType(next.key);
                            if (rt.isPropertyKey()) {
//                                PropertyKey pkey = (PropertyKey)vertex.getTypeManager().getPropertyKey(rt.name());
//                                log.debug("Retrieved key {} for name \"{}\"", pkey, rt.name());
//                                frel.property(pkey.label(), next.value);
                                te.property(rt.name(), next.value);
                            } else {
                                throw new RuntimeException("Metaedges are not supported");
//                                assert next.value instanceof Long;
//                                EdgeLabel el = (EdgeLabel)vertex.getTypeManager().getEdgeLabel(rt.name());
//                                log.debug("Retrieved ege label {} for name \"{}\"", el, rt.name());
//                                frel.setProperty(el, new FaunusVertex(configuration,(Long)next.value));
                            }
                        }
                    }
                }

//                // Iterate over and copy the relation's metaproperties
//                if (relation.hasProperties()) {
//                    // Load relation properties
//                    for (final LongObjectCursor<Object> next : relation) {
//                        assert next.value != null;
//                        RelationType rt = typeManager.getExistingRelationType(next.key);
//                        if (rt.isPropertyKey()) {
//                            PropertyKey pkey = (PropertyKey)vertex.getTypeManager().getPropertyKey(rt.name());
//                            log.debug("Retrieved key {} for name \"{}\"", pkey, rt.name());
//                            frel.property(pkey.label(), next.value);
//                        } else {
//                            assert next.value instanceof Long;
//                            EdgeLabel el = (EdgeLabel)vertex.getTypeManager().getEdgeLabel(rt.name());
//                            log.debug("Retrieved ege label {} for name \"{}\"", el, rt.name());
//                            frel.setProperty(el, new FaunusVertex(configuration,(Long)next.value));
//                        }
//                    }
//                    for (TitanRelation rel : frel.query().queryAll().relations())
//                        ((FaunusRelation)rel).setLifeCycle(ElementLifeCycle.Loaded);
//                }
//                frel.setLifeCycle(ElementLifeCycle.Loaded);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        //vertex.setLifeCycle(ElementLifeCycle.Loaded);

        /*Since we are filtering out system relation types, we might end up with vertices that have no incident relations.
         This is especially true for schema vertices. Those are filtered out.     */
        if (!foundVertexState) {
            log.trace("Vertex {} has unknown lifecycle state", vertexId);
            return null;
        } else if (!tv.edgeIterator(Direction.BOTH).hasNext() && !tv.propertyIterator().hasNext()) {
            log.trace("Vertex {} has no relations", vertexId);
            return null;
        }
        return tv;
    }

    public TinkerVertex getOrCreateVertex(final long vertexId, final String label, final TinkerGraph tg) {
        TinkerVertex v;

        try {
            v = (TinkerVertex)tg.v(vertexId);
        } catch (NoSuchElementException e) {
            if (null != label) {
                v = (TinkerVertex) tg.addVertex(T.label, label, T.id, vertexId);
            } else {
                v = (TinkerVertex) tg.addVertex(T.id, vertexId);
            }
        }

        return v;
    }

    public void close() {
        setup.close();
    }

}
