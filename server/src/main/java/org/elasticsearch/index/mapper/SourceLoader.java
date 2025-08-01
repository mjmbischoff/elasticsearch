/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.fieldvisitor.LeafStoredFieldLoader;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceFilter;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads source {@code _source} during a GET or {@code _search}.
 */
public interface SourceLoader {
    /**
     * Does this {@link SourceLoader} reorder field values?
     */
    boolean reordersFieldValues();

    /**
     * Build the loader for some segment.
     */
    Leaf leaf(LeafReader reader, int[] docIdsInLeaf) throws IOException;

    /**
     * Stream containing all non-{@code _source} stored fields required
     * to build the {@code _source}.
     */
    Set<String> requiredStoredFields();

    /**
     * Loads {@code _source} from some segment.
     */
    interface Leaf {
        /**
         * Load the {@code _source} for a document.
         * @param storedFields a loader for stored fields
         * @param docId the doc to load
         */
        Source source(LeafStoredFieldLoader storedFields, int docId) throws IOException;

        /**
         * Write the {@code _source} for a document in the provided {@link XContentBuilder}.
         * @param storedFields a loader for stored fields
         * @param docId the doc to load
         * @param b the builder to write the xcontent
         */
        void write(LeafStoredFieldLoader storedFields, int docId, XContentBuilder b) throws IOException;
    }

    /**
     * Load {@code _source} from a stored field.
     */
    SourceLoader FROM_STORED_SOURCE = new Stored(null);

    class Stored implements SourceLoader {
        final SourceFilter filter;

        public Stored(@Nullable SourceFilter filter) {
            this.filter = filter;
        }

        @Override
        public boolean reordersFieldValues() {
            return false;
        }

        @Override
        public Leaf leaf(LeafReader reader, int[] docIdsInLeaf) {
            return new Leaf() {
                @Override
                public Source source(LeafStoredFieldLoader storedFields, int docId) throws IOException {
                    var res = Source.fromBytes(storedFields.source());
                    return filter == null ? res : res.filter(filter);
                }

                @Override
                public void write(LeafStoredFieldLoader storedFields, int docId, XContentBuilder builder) throws IOException {
                    Source source = source(storedFields, docId);
                    builder.rawValue(source.internalSourceRef().streamInput(), source.sourceContentType());
                }
            };
        }

        @Override
        public Set<String> requiredStoredFields() {
            return Set.of();
        }
    }

    /**
     * Reconstructs {@code _source} from doc values and stored fields.
     */
    class Synthetic implements SourceLoader {
        private final SourceFilter filter;
        private final Supplier<SyntheticFieldLoader> syntheticFieldLoaderLeafSupplier;
        private final Set<String> requiredStoredFields;
        private final SourceFieldMetrics metrics;

        /**
         * Creates a {@link SourceLoader} to reconstruct {@code _source} from doc values anf stored fields.
         * @param filter An optional filter to include/exclude fields.
         * @param fieldLoaderSupplier A supplier to create {@link SyntheticFieldLoader}, one for each leaf.
         * @param metrics Metrics for profiling.
         */
        public Synthetic(@Nullable SourceFilter filter, Supplier<SyntheticFieldLoader> fieldLoaderSupplier, SourceFieldMetrics metrics) {
            this.syntheticFieldLoaderLeafSupplier = fieldLoaderSupplier;
            this.requiredStoredFields = syntheticFieldLoaderLeafSupplier.get()
                .storedFieldLoaders()
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            this.metrics = metrics;
            this.filter = filter;
        }

        @Override
        public boolean reordersFieldValues() {
            return true;
        }

        @Override
        public Set<String> requiredStoredFields() {
            return requiredStoredFields;
        }

        @Override
        public Leaf leaf(LeafReader reader, int[] docIdsInLeaf) throws IOException {
            SyntheticFieldLoader loader = syntheticFieldLoaderLeafSupplier.get();
            return new LeafWithMetrics(new SyntheticLeaf(filter, loader, loader.docValuesLoader(reader, docIdsInLeaf)), metrics);
        }

        private record LeafWithMetrics(Leaf leaf, SourceFieldMetrics metrics) implements Leaf {

            @Override
            public Source source(LeafStoredFieldLoader storedFields, int docId) throws IOException {
                long startTime = metrics.getRelativeTimeSupplier().getAsLong();

                var source = leaf.source(storedFields, docId);

                TimeValue duration = TimeValue.timeValueMillis(metrics.getRelativeTimeSupplier().getAsLong() - startTime);
                metrics.recordSyntheticSourceLoadLatency(duration);

                return source;
            }

            @Override
            public void write(LeafStoredFieldLoader storedFields, int docId, XContentBuilder b) throws IOException {
                long startTime = metrics.getRelativeTimeSupplier().getAsLong();

                leaf.write(storedFields, docId, b);

                TimeValue duration = TimeValue.timeValueMillis(metrics.getRelativeTimeSupplier().getAsLong() - startTime);
                metrics.recordSyntheticSourceLoadLatency(duration);
            }
        }

        private static class SyntheticLeaf implements Leaf {
            private final SourceFilter filter;
            private final SyntheticFieldLoader loader;
            private final SyntheticFieldLoader.DocValuesLoader docValuesLoader;
            private final Map<String, SyntheticFieldLoader.StoredFieldLoader> storedFieldLoaders;

            private SyntheticLeaf(SourceFilter filter, SyntheticFieldLoader loader, SyntheticFieldLoader.DocValuesLoader docValuesLoader) {
                this.filter = filter;
                this.loader = loader;
                this.docValuesLoader = docValuesLoader;
                this.storedFieldLoaders = Map.copyOf(
                    loader.storedFieldLoaders().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                );
            }

            @Override
            public Source source(LeafStoredFieldLoader storedFieldLoader, int docId) throws IOException {
                try (XContentBuilder b = new XContentBuilder(JsonXContent.jsonXContent, new ByteArrayOutputStream())) {
                    write(storedFieldLoader, docId, b);
                    return Source.fromBytes(BytesReference.bytes(b), b.contentType());
                }
            }

            @Override
            public void write(LeafStoredFieldLoader storedFieldLoader, int docId, XContentBuilder b) throws IOException {
                // Maps the names of existing objects to lists of ignored fields they contain.
                Map<String, List<IgnoredSourceFieldMapper.NameValue>> objectsWithIgnoredFields = null;

                for (Map.Entry<String, List<Object>> e : storedFieldLoader.storedFields().entrySet()) {
                    SyntheticFieldLoader.StoredFieldLoader loader = storedFieldLoaders.get(e.getKey());
                    if (loader != null) {
                        loader.load(e.getValue());
                    }
                    if (IgnoredSourceFieldMapper.NAME.equals(e.getKey())) {
                        for (Object value : e.getValue()) {
                            if (objectsWithIgnoredFields == null) {
                                objectsWithIgnoredFields = new HashMap<>();
                            }
                            IgnoredSourceFieldMapper.NameValue nameValue = IgnoredSourceFieldMapper.decode(value);
                            if (filter != null
                                && filter.isPathFiltered(nameValue.name(), XContentDataHelper.isEncodedObject(nameValue.value()))) {
                                // This path is filtered by the include/exclude rules
                                continue;
                            }
                            objectsWithIgnoredFields.computeIfAbsent(nameValue.getParentFieldName(), k -> new ArrayList<>()).add(nameValue);
                        }
                    }
                }
                if (objectsWithIgnoredFields != null) {
                    loader.setIgnoredValues(objectsWithIgnoredFields);
                }
                if (docValuesLoader != null) {
                    docValuesLoader.advanceToDoc(docId);
                }

                loader.prepare();

                // TODO accept a requested xcontent type
                if (loader.hasValue()) {
                    loader.write(b);
                } else {
                    b.startObject().endObject();
                }
            }
        }
    }

    /**
     * Load a field for {@link Synthetic}.
     * <p>
     * {@link SyntheticFieldLoader}s load values through objects vended
     * by their {@link #storedFieldLoaders} and {@link #docValuesLoader}
     * methods. Then you call {@link #write} to write the values to an
     * {@link XContentBuilder} which also clears them.
     * <p>
     * This two loaders and one writer setup is specifically designed to
     * efficiently load the {@code _source} of indices that have thousands
     * of fields declared in the mapping but that only have values for
     * dozens of them. It handles this in a few ways:
     * <ul>
     *     <li>{@link #docValuesLoader} must be called once per document
     *         per field to load the doc values, but detects up front if
     *         there are no doc values for that field. It's linear with
     *         the number of fields, whether or not they have values,
     *         but skips entirely missing fields.</li>
     *     <li>{@link #storedFieldLoaders} are only called when the
     *         document contains a stored field with the appropriate name.
     *         So it's fine to have thousands of these declared in the
     *         mapping and you don't really pay much to load them. Just
     *         the cost to build {@link Map} used to address them.</li>
     *     <li>Object fields that don't have any values loaded by either
     *         means bail out of the loading process and don't pass
     *         control down to any of their children. Thus it's fine
     *         to declare huge object structures in the mapping and
     *         you only spend time iterating the ones you need. Or that
     *         have doc values.</li>
     * </ul>
     */
    interface SyntheticFieldLoader {
        /**
         * Load no values.
         */
        SyntheticFieldLoader NOTHING = new SyntheticFieldLoader() {
            @Override
            public Stream<Map.Entry<String, StoredFieldLoader>> storedFieldLoaders() {
                return Stream.of();
            }

            @Override
            public DocValuesLoader docValuesLoader(LeafReader leafReader, int[] docIdsInLeaf) throws IOException {
                return null;
            }

            @Override
            public boolean hasValue() {
                return false;
            }

            @Override
            public void write(XContentBuilder b) {}

            @Override
            public void reset() {

            }

            @Override
            public String fieldName() {
                return "";
            }
        };

        /**
         * A {@link Stream} mapping stored field paths to a place to put them
         * so they can be included in the next document.
         */
        Stream<Map.Entry<String, StoredFieldLoader>> storedFieldLoaders();

        /**
         * Build something to load doc values for this field or return
         * {@code null} if there are no doc values for this field to
         * load.
         *
         * @param docIdsInLeaf can be null.
         */
        DocValuesLoader docValuesLoader(LeafReader leafReader, int[] docIdsInLeaf) throws IOException;

        /**
         Perform any preprocessing needed before producing synthetic source
         and deduce whether this mapper (and its children, if any) have values to write.
         The expectation is for this method to be called before {@link SyntheticFieldLoader#hasValue()}
         and {@link SyntheticFieldLoader#write(XContentBuilder)} are used.
         */
        default void prepare() {
            // Noop
        }

        /**
         * Has this field loaded any values for this document?
         */
        boolean hasValue();

        /**
         * Write values for this document.
         */
        void write(XContentBuilder b) throws IOException;

        /**
         * Allows for identifying and tracking additional field values to include in the field source.
         * @param objectsWithIgnoredFields maps object names to lists of fields they contain with special source handling
         * @return true if any matching fields are identified
         */
        default boolean setIgnoredValues(Map<String, List<IgnoredSourceFieldMapper.NameValue>> objectsWithIgnoredFields) {
            return false;
        }

        /**
         * Returns the canonical field name for this loader.
         */
        String fieldName();

        /**
         * Resets the loader to remove any stored data and prepare it for processing new document.
         * This is an alternative code path to {@link  SyntheticFieldLoader#write} that is executed
         * when values are loaded but not written.
         * Loaders are expected to also reset their state after writing currently present data.
         */
        void reset();

        /**
         * Sync for stored field values.
         */
        interface StoredFieldLoader {
            /**
             * Loads values read from a corresponding stored field into this loader.
             */
            void load(List<Object> values);
        }

        /**
         * Loads doc values for a field.
         */
        interface DocValuesLoader {
            /**
             * Load the doc values for this field.
             *
             * @return whether or not there are any values for this field
             */
            boolean advanceToDoc(int docId) throws IOException;
        }
    }

    /**
     * Synthetic field loader that uses only doc values to load synthetic source values.
     */
    abstract class DocValuesBasedSyntheticFieldLoader implements SyntheticFieldLoader {
        @Override
        public Stream<Map.Entry<String, StoredFieldLoader>> storedFieldLoaders() {
            return Stream.empty();
        }

        @Override
        public void reset() {
            // Not applicable to loaders using only doc values
            // since DocValuesLoader#advanceToDoc will reset the state anyway.
        }
    }

    class SyntheticVectors implements SourceLoader {
        final SourceLoader sourceLoader;
        final SyntheticVectorsLoader patchLoader;

        SyntheticVectors(@Nullable SourceFilter sourceFilter, SyntheticVectorsLoader patchLoader) {
            this.sourceLoader = sourceFilter == null ? FROM_STORED_SOURCE : new Stored(sourceFilter);
            this.patchLoader = patchLoader;
        }

        @Override
        public boolean reordersFieldValues() {
            return false;
        }

        @Override
        public Set<String> requiredStoredFields() {
            return sourceLoader.requiredStoredFields();
        }

        @Override
        public Leaf leaf(LeafReader reader, int[] docIdsInLeaf) throws IOException {
            var sourceLeaf = sourceLoader.leaf(reader, docIdsInLeaf);
            var patchLeaf = patchLoader.leaf(reader.getContext());
            return new Leaf() {
                @Override
                public Source source(LeafStoredFieldLoader storedFields, int docId) throws IOException {
                    Source source = sourceLeaf.source(storedFields, docId);
                    if (patchLeaf == null) {
                        return source;
                    }
                    List<SyntheticVectorPatch> patches = new ArrayList<>();
                    patchLeaf.load(docId, patches);
                    if (patches.size() == 0) {
                        return source;
                    }
                    return applySyntheticVectors(source, patches);
                }

                @Override
                public void write(LeafStoredFieldLoader storedFields, int docId, XContentBuilder b) throws IOException {
                    throw new IllegalStateException("This operation is not allowed in the current context");
                }
            };
        }
    }

    /**
     * Applies a list of {@link SyntheticVectorPatch} instances to the given {@link Source}.
     *
     * @param originalSource the original source object
     * @param patches        the list of patches to apply
     * @return a new {@link Source} with the patches applied
     */
    static Source applySyntheticVectors(Source originalSource, List<SyntheticVectorPatch> patches) {
        Map<String, Object> newMap = originalSource.source();
        applyPatches("", newMap, patches);
        return Source.fromMap(newMap, originalSource.sourceContentType());
    }

    /**
     * Recursively applies synthetic vector patches to a nested map.
     *
     * @param rootPath the current root path for nested structures
     * @param map      the map to apply patches to
     * @param patches  the list of patches to apply
     */
    private static void applyPatches(String rootPath, Map<String, Object> map, List<SyntheticVectorPatch> patches) {
        for (SyntheticVectorPatch patch : patches) {
            if (patch instanceof LeafSyntheticVectorPath leaf) {
                String key = extractRelativePath(rootPath, leaf.fullPath());
                XContentMapValues.insertValue(key, map, leaf.value(), false);
            } else if (patch instanceof NestedSyntheticVectorPath nested) {
                String nestedPath = extractRelativePath(rootPath, nested.fullPath());
                List<Map<?, ?>> nestedMaps = XContentMapValues.extractNestedSources(nestedPath, map);
                for (SyntheticVectorPatch childPatch : nested.children()) {
                    if (childPatch instanceof NestedOffsetSyntheticVectorPath offsetPatch) {
                        Map<String, Object> nestedMap = XContentMapValues.nodeMapValue(nestedMaps.get(offsetPatch.offset()), nestedPath);
                        applyPatches(nested.fullPath(), nestedMap, offsetPatch.children());
                    } else {
                        throw new IllegalStateException(
                            "Unexpected child patch type of " + patch.getClass().getSimpleName() + " in nested structure."
                        );
                    }
                }
            } else {
                throw new IllegalStateException("Unknown patch type: " + patch.getClass().getSimpleName());
            }
        }
    }

    private static String extractRelativePath(String rootPath, String fullPath) {
        return rootPath.isEmpty() ? fullPath : fullPath.substring(rootPath.length() + 1);
    }

    /**
     * Represents a patch to be applied to a source structure.
     */
    sealed interface SyntheticVectorPatch permits NestedSyntheticVectorPath, NestedOffsetSyntheticVectorPath, LeafSyntheticVectorPath {}

    /**
     * A patch representing a nested path with further child patches.
     *
     * @param fullPath the full dot-separated path
     * @param children the list of child patches
     */
    record NestedSyntheticVectorPath(String fullPath, List<SyntheticVectorPatch> children) implements SyntheticVectorPatch {}

    /**
     * A patch representing an indexed child within a nested structure.
     *
     * @param offset   the index of the nested element
     * @param children the list of child patches to apply at this offset
     */
    record NestedOffsetSyntheticVectorPath(int offset, List<SyntheticVectorPatch> children) implements SyntheticVectorPatch {}

    /**
     * A patch representing a leaf field with a value to be applied.
     *
     * @param fullPath the fully-qualified field name
     * @param value     the value to assign
     */
    record LeafSyntheticVectorPath(String fullPath, Object value) implements SyntheticVectorPatch {}

    interface SyntheticVectorsLoader {
        /**
         * Returns a leaf loader if the provided context contains patches for the specified field;
         * returns null otherwise.
         */
        SyntheticVectorsLoader.Leaf leaf(LeafReaderContext context) throws IOException;

        interface Leaf {
            /**
             * Loads all patches for this field associated with the provided document into the specified {@code acc} list.
             */
            void load(int doc, List<SyntheticVectorPatch> acc) throws IOException;
        }
    }
}
