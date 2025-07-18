/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.store.BaseDirectoryWrapper;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.MockPageCacheRecycler;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.compute.aggregation.CountAggregatorFunction;
import org.elasticsearch.compute.aggregation.ValuesLongAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DocBlock;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.lucene.DataPartitioning;
import org.elasticsearch.compute.lucene.LuceneOperator;
import org.elasticsearch.compute.lucene.LuceneSliceQueue;
import org.elasticsearch.compute.lucene.LuceneSourceOperator;
import org.elasticsearch.compute.lucene.LuceneSourceOperatorTests;
import org.elasticsearch.compute.lucene.ShardContext;
import org.elasticsearch.compute.lucene.read.ValuesSourceReaderOperator;
import org.elasticsearch.compute.operator.AbstractPageMappingOperator;
import org.elasticsearch.compute.operator.Driver;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.HashAggregationOperator;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.operator.OrdinalsGroupingOperator;
import org.elasticsearch.compute.operator.PageConsumerOperator;
import org.elasticsearch.compute.operator.RowInTableLookupOperator;
import org.elasticsearch.compute.operator.ShuffleDocsOperator;
import org.elasticsearch.compute.test.BlockTestUtils;
import org.elasticsearch.compute.test.OperatorTestCase;
import org.elasticsearch.compute.test.SequenceLongBlockSourceOperator;
import org.elasticsearch.compute.test.TestDriverFactory;
import org.elasticsearch.compute.test.TestResultPageSinkOperator;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.BlockDocValuesReader;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.elasticsearch.compute.aggregation.AggregatorMode.FINAL;
import static org.elasticsearch.compute.aggregation.AggregatorMode.INITIAL;
import static org.elasticsearch.compute.test.OperatorTestCase.randomPageSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * This venerable test builds {@link Driver}s by hand and runs them together, simulating
 * whole runs without needing to involve ESQL-proper. It's a wonderful place to integration
 * test new ideas, and it was the first tests the compute engine ever had. But as we plug
 * these things into ESQL tests should leave here and just run in csv-spec tests. Or move
 * into unit tests for the operators themselves.
 * <p>
 *     TODO move any of these we can to unit tests for the operator.
 * </p>
 */
public class OperatorTests extends MapperServiceTestCase {

    public void testQueryOperator() throws IOException {
        Map<BytesRef, Long> docs = new HashMap<>();
        CheckedConsumer<DirectoryReader, IOException> verifier = reader -> {
            final long from = randomBoolean() ? Long.MIN_VALUE : randomLongBetween(0, 10000);
            final long to = randomBoolean() ? Long.MAX_VALUE : randomLongBetween(from, from + 10000);
            final Query query = LongPoint.newRangeQuery("pt", from, to);
            LuceneOperator.Factory factory = luceneOperatorFactory(
                reader,
                List.of(new LuceneSliceQueue.QueryAndTags(query, List.of())),
                LuceneOperator.NO_LIMIT
            );
            List<Driver> drivers = new ArrayList<>();
            try {
                Set<Integer> actualDocIds = ConcurrentCollections.newConcurrentSet();
                for (int t = 0; t < factory.taskConcurrency(); t++) {
                    PageConsumerOperator docCollector = new PageConsumerOperator(page -> {
                        DocVector docVector = page.<DocBlock>getBlock(0).asVector();
                        IntVector doc = docVector.docs();
                        IntVector segment = docVector.segments();
                        for (int i = 0; i < doc.getPositionCount(); i++) {
                            int docBase = reader.leaves().get(segment.getInt(i)).docBase;
                            int docId = docBase + doc.getInt(i);
                            assertTrue("duplicated docId=" + docId, actualDocIds.add(docId));
                        }
                    });
                    DriverContext driverContext = driverContext();
                    drivers.add(TestDriverFactory.create(driverContext, factory.get(driverContext), List.of(), docCollector));
                }
                OperatorTestCase.runDriver(drivers);
                Set<Integer> expectedDocIds = searchForDocIds(reader, query);
                assertThat("query=" + query, actualDocIds, equalTo(expectedDocIds));
                drivers.stream().map(Driver::driverContext).forEach(OperatorTests::assertDriverContext);
            } finally {
                Releasables.close(drivers);
            }
        };

        try (Directory dir = newDirectory(); RandomIndexWriter w = new RandomIndexWriter(random(), dir)) {
            int numDocs = randomIntBetween(0, 10_000);
            for (int i = 0; i < numDocs; i++) {
                Document d = new Document();
                long point = randomLongBetween(0, 5000);
                d.add(new LongPoint("pt", point));
                BytesRef id = Uid.encodeId("id-" + randomIntBetween(0, 5000));
                d.add(new Field("id", id, KeywordFieldMapper.Defaults.FIELD_TYPE));
                if (docs.put(id, point) != null) {
                    w.updateDocument(new Term("id", id), d);
                } else {
                    w.addDocument(d);
                }
            }
            try (DirectoryReader reader = w.getReader()) {
                verifier.accept(reader);
            }
        }
    }

    public void testGroupingWithOrdinals() throws Exception {
        DriverContext driverContext = driverContext();
        BlockFactory blockFactory = driverContext.blockFactory();

        final String gField = "g";
        final int numDocs = 2856; // between(100, 10000);
        final Map<BytesRef, Long> expectedCounts = new HashMap<>();
        int keyLength = randomIntBetween(1, 10);
        try (BaseDirectoryWrapper dir = newDirectory(); RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
            for (int i = 0; i < numDocs; i++) {
                Document doc = new Document();
                BytesRef key = new BytesRef(randomByteArrayOfLength(keyLength));
                SortedSetDocValuesField docValuesField = new SortedSetDocValuesField(gField, key);
                doc.add(docValuesField);
                writer.addDocument(doc);
                expectedCounts.compute(key, (k, v) -> v == null ? 1 : v + 1);
            }
            writer.commit();
            Map<BytesRef, Long> actualCounts = new HashMap<>();

            try (DirectoryReader reader = writer.getReader()) {
                List<Operator> operators = new ArrayList<>();
                if (randomBoolean()) {
                    operators.add(new ShuffleDocsOperator(blockFactory));
                }
                operators.add(new AbstractPageMappingOperator() {
                    @Override
                    protected Page process(Page page) {
                        return page.appendBlock(driverContext.blockFactory().newConstantIntBlockWith(1, page.getPositionCount()));
                    }

                    @Override
                    public String toString() {
                        return "Add(1)";
                    }
                });
                operators.add(
                    new OrdinalsGroupingOperator(
                        shardIdx -> new KeywordFieldMapper.KeywordFieldType("g").blockLoader(mockBlContext()),
                        List.of(new ValuesSourceReaderOperator.ShardContext(reader, () -> SourceLoader.FROM_STORED_SOURCE, 0.2)),
                        ElementType.BYTES_REF,
                        0,
                        gField,
                        List.of(CountAggregatorFunction.supplier().groupingAggregatorFactory(INITIAL, List.of(1))),
                        randomPageSize(),
                        driverContext
                    )
                );
                operators.add(
                    new HashAggregationOperator(
                        List.of(CountAggregatorFunction.supplier().groupingAggregatorFactory(FINAL, List.of(1, 2))),
                        () -> BlockHash.build(
                            List.of(new BlockHash.GroupSpec(0, ElementType.BYTES_REF)),
                            driverContext.blockFactory(),
                            randomPageSize(),
                            false
                        ),
                        driverContext
                    )
                );
                Driver driver = TestDriverFactory.create(
                    driverContext,
                    luceneOperatorFactory(
                        reader,
                        List.of(new LuceneSliceQueue.QueryAndTags(new MatchAllDocsQuery(), List.of())),
                        LuceneOperator.NO_LIMIT
                    ).get(driverContext),
                    operators,
                    new PageConsumerOperator(page -> {
                        BytesRefBlock keys = page.getBlock(0);
                        LongBlock counts = page.getBlock(1);
                        for (int i = 0; i < keys.getPositionCount(); i++) {
                            BytesRef spare = new BytesRef();
                            keys.getBytesRef(i, spare);
                            actualCounts.put(BytesRef.deepCopyOf(spare), counts.getLong(i));
                        }
                        page.releaseBlocks();
                    })
                );
                OperatorTestCase.runDriver(driver);
                assertThat(actualCounts, equalTo(expectedCounts));
                assertDriverContext(driverContext);
                org.elasticsearch.common.util.MockBigArrays.ensureAllArraysAreReleased();
            }
        }
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    // TODO: Remove ordinals grouping operator or enable it GroupingAggregatorFunctionTestCase
    public void testValuesWithOrdinalGrouping() throws Exception {
        DriverContext driverContext = driverContext();
        BlockFactory blockFactory = driverContext.blockFactory();

        final int numDocs = between(100, 1000);
        Map<BytesRef, Set<Long>> expectedValues = new HashMap<>();
        try (BaseDirectoryWrapper dir = newDirectory(); RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
            String VAL_NAME = "val";
            String KEY_NAME = "key";
            for (int i = 0; i < numDocs; i++) {
                Document doc = new Document();
                BytesRef key = new BytesRef(Integer.toString(between(1, 100)));
                SortedSetDocValuesField keyField = new SortedSetDocValuesField(KEY_NAME, key);
                doc.add(keyField);
                if (randomBoolean()) {
                    int numValues = between(0, 2);
                    for (int v = 0; v < numValues; v++) {
                        long val = between(1, 1000);
                        var valuesField = new SortedNumericDocValuesField(VAL_NAME, val);
                        doc.add(valuesField);
                        expectedValues.computeIfAbsent(key, k -> new HashSet<>()).add(val);
                    }
                }
                writer.addDocument(doc);
            }
            writer.commit();
            try (DirectoryReader reader = writer.getReader()) {
                List<Operator> operators = new ArrayList<>();
                if (randomBoolean()) {
                    operators.add(new ShuffleDocsOperator(blockFactory));
                }
                operators.add(
                    new ValuesSourceReaderOperator(
                        blockFactory,
                        List.of(
                            new ValuesSourceReaderOperator.FieldInfo(
                                VAL_NAME,
                                ElementType.LONG,
                                unused -> new BlockDocValuesReader.LongsBlockLoader(VAL_NAME)
                            )
                        ),
                        List.of(new ValuesSourceReaderOperator.ShardContext(reader, () -> {
                            throw new UnsupportedOperationException();
                        }, 0.2)),
                        0
                    )
                );
                operators.add(
                    new OrdinalsGroupingOperator(
                        shardIdx -> new KeywordFieldMapper.KeywordFieldType(KEY_NAME).blockLoader(mockBlContext()),
                        List.of(new ValuesSourceReaderOperator.ShardContext(reader, () -> SourceLoader.FROM_STORED_SOURCE, 0.2)),
                        ElementType.BYTES_REF,
                        0,
                        KEY_NAME,
                        List.of(new ValuesLongAggregatorFunctionSupplier().groupingAggregatorFactory(INITIAL, List.of(1))),
                        randomPageSize(),
                        driverContext
                    )
                );
                operators.add(
                    new HashAggregationOperator(
                        List.of(new ValuesLongAggregatorFunctionSupplier().groupingAggregatorFactory(FINAL, List.of(1))),
                        () -> BlockHash.build(
                            List.of(new BlockHash.GroupSpec(0, ElementType.BYTES_REF)),
                            driverContext.blockFactory(),
                            randomPageSize(),
                            false
                        ),
                        driverContext
                    )
                );
                Map<BytesRef, Set<Long>> actualValues = new HashMap<>();
                Driver driver = TestDriverFactory.create(
                    driverContext,
                    luceneOperatorFactory(
                        reader,
                        List.of(new LuceneSliceQueue.QueryAndTags(new MatchAllDocsQuery(), List.of())),
                        LuceneOperator.NO_LIMIT
                    ).get(driverContext),
                    operators,
                    new PageConsumerOperator(page -> {
                        BytesRefBlock keyBlock = page.getBlock(0);
                        LongBlock valueBlock = page.getBlock(1);
                        BytesRef spare = new BytesRef();
                        for (int p = 0; p < page.getPositionCount(); p++) {
                            var key = keyBlock.getBytesRef(p, spare);
                            int valueCount = valueBlock.getValueCount(p);
                            for (int i = 0; i < valueCount; i++) {
                                long val = valueBlock.getLong(valueBlock.getFirstValueIndex(p) + i);
                                boolean added = actualValues.computeIfAbsent(BytesRef.deepCopyOf(key), k -> new HashSet<>()).add(val);
                                assertTrue(actualValues.toString(), added);
                            }
                        }
                        page.releaseBlocks();
                    })
                );
                OperatorTestCase.runDriver(driver);
                assertDriverContext(driverContext);
                assertThat(actualValues, equalTo(expectedValues));
                org.elasticsearch.common.util.MockBigArrays.ensureAllArraysAreReleased();
            }
        }
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    public void testPushRoundToToQuery() throws IOException {
        long firstGroupMax = randomLong();
        long secondGroupMax = randomLong();
        long thirdGroupMax = randomLong();

        CheckedConsumer<DirectoryReader, IOException> verifier = reader -> {
            Query firstGroupQuery = LongPoint.newRangeQuery("g", Long.MIN_VALUE, 99);
            Query secondGroupQuery = LongPoint.newRangeQuery("g", 100, 9999);
            Query thirdGroupQuery = LongPoint.newRangeQuery("g", 10000, Long.MAX_VALUE);

            LuceneSliceQueue.QueryAndTags firstGroupQueryAndTags = new LuceneSliceQueue.QueryAndTags(firstGroupQuery, List.of(0L));
            LuceneSliceQueue.QueryAndTags secondGroupQueryAndTags = new LuceneSliceQueue.QueryAndTags(secondGroupQuery, List.of(100L));
            LuceneSliceQueue.QueryAndTags thirdGroupQueryAndTags = new LuceneSliceQueue.QueryAndTags(thirdGroupQuery, List.of(10000L));

            LuceneOperator.Factory factory = luceneOperatorFactory(
                reader,
                List.of(firstGroupQueryAndTags, secondGroupQueryAndTags, thirdGroupQueryAndTags),
                LuceneOperator.NO_LIMIT
            );
            ValuesSourceReaderOperator.Factory load = new ValuesSourceReaderOperator.Factory(
                List.of(
                    new ValuesSourceReaderOperator.FieldInfo("v", ElementType.LONG, f -> new BlockDocValuesReader.LongsBlockLoader("v"))
                ),
                List.of(new ValuesSourceReaderOperator.ShardContext(reader, () -> {
                    throw new UnsupportedOperationException();
                }, 0.8)),
                0
            );
            List<Page> pages = new ArrayList<>();
            DriverContext driverContext = driverContext();
            try (
                Driver driver = TestDriverFactory.create(
                    driverContext,
                    factory.get(driverContext),
                    List.of(load.get(driverContext)),
                    new TestResultPageSinkOperator(pages::add)
                )
            ) {
                OperatorTestCase.runDriver(driver);
            }
            assertDriverContext(driverContext);

            boolean sawFirstMax = false;
            boolean sawSecondMax = false;
            boolean sawThirdMax = false;
            for (Page page : pages) {
                logger.error("ADFA {}", page);
                LongVector group = page.<LongBlock>getBlock(1).asVector();
                LongVector value = page.<LongBlock>getBlock(2).asVector();
                for (int p = 0; p < page.getPositionCount(); p++) {
                    long g = group.getLong(p);
                    long v = value.getLong(p);
                    switch ((int) g) {
                        case 0 -> {
                            assertThat(v, lessThanOrEqualTo(firstGroupMax));
                            sawFirstMax |= v == firstGroupMax;
                        }
                        case 100 -> {
                            assertThat(v, lessThanOrEqualTo(secondGroupMax));
                            sawSecondMax |= v == secondGroupMax;
                        }
                        case 10000 -> {
                            assertThat(v, lessThanOrEqualTo(thirdGroupMax));
                            sawThirdMax |= v == thirdGroupMax;
                        }
                        default -> throw new IllegalArgumentException("Unknown group [" + g + "]");
                    }
                }
            }
            assertTrue(sawFirstMax);
            assertTrue(sawSecondMax);
            assertTrue(sawThirdMax);
        };

        try (Directory dir = newDirectory(); RandomIndexWriter w = new RandomIndexWriter(random(), dir)) {
            int numDocs = randomIntBetween(0, 10_000);
            for (int i = 0; i < numDocs; i++) {
                long g, v;
                switch (between(0, 2)) {
                    case 0 -> {
                        g = randomLongBetween(Long.MIN_VALUE, 99);
                        v = randomLongBetween(Long.MIN_VALUE, firstGroupMax);
                    }
                    case 1 -> {
                        g = randomLongBetween(100, 9999);
                        v = randomLongBetween(Long.MIN_VALUE, secondGroupMax);
                    }
                    case 2 -> {
                        g = randomLongBetween(10000, Long.MAX_VALUE);
                        v = randomLongBetween(Long.MIN_VALUE, thirdGroupMax);
                    }
                    default -> throw new IllegalArgumentException();
                }
                w.addDocument(List.of(new LongField("g", g, Field.Store.NO), new LongField("v", v, Field.Store.NO)));
            }
            w.addDocument(List.of(new LongField("g", 0, Field.Store.NO), new LongField("v", firstGroupMax, Field.Store.NO)));
            w.addDocument(List.of(new LongField("g", 200, Field.Store.NO), new LongField("v", secondGroupMax, Field.Store.NO)));
            w.addDocument(List.of(new LongField("g", 20000, Field.Store.NO), new LongField("v", thirdGroupMax, Field.Store.NO)));

            try (DirectoryReader reader = w.getReader()) {
                verifier.accept(reader);
            }
        }
    }

    private static Set<Integer> searchForDocIds(IndexReader reader, Query query) throws IOException {
        IndexSearcher searcher = new IndexSearcher(reader);
        Set<Integer> docIds = new HashSet<>();
        searcher.search(query, new Collector() {
            @Override
            public LeafCollector getLeafCollector(LeafReaderContext context) {
                return new LeafCollector() {
                    @Override
                    public void setScorer(Scorable scorer) {

                    }

                    @Override
                    public void collect(int doc) {
                        int docId = context.docBase + doc;
                        assertTrue(docIds.add(docId));
                    }
                };
            }

            @Override
            public ScoreMode scoreMode() {
                return ScoreMode.COMPLETE_NO_SCORES;
            }
        });
        return docIds;
    }

    public void testHashLookup() {
        // TODO move this to an integration test once we've plugged in the lookup
        DriverContext driverContext = driverContext();
        Map<Long, Integer> primeOrds = new TreeMap<>();
        Block primesBlock;
        try (LongBlock.Builder primes = driverContext.blockFactory().newLongBlockBuilder(30)) {
            boolean[] sieve = new boolean[100];
            Arrays.fill(sieve, true);
            sieve[0] = false;
            sieve[1] = false;
            int prime = 2;
            while (prime < 100) {
                if (false == sieve[prime]) {
                    prime++;
                    continue;
                }
                primes.appendLong(prime);
                primeOrds.put((long) prime, primeOrds.size());
                for (int m = prime + prime; m < sieve.length; m += prime) {
                    sieve[m] = false;
                }
                prime++;
            }
            primesBlock = primes.build();
        }
        try {
            List<Long> values = new ArrayList<>();
            List<Object> expectedValues = new ArrayList<>();
            List<Object> expectedPrimeOrds = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long v = i % 10 == 0 ? randomFrom(primeOrds.keySet()) : randomLongBetween(0, 100);
                values.add(v);
                expectedValues.add(v);
                expectedPrimeOrds.add(primeOrds.get(v));
            }

            var actualValues = new ArrayList<>();
            var actualPrimeOrds = new ArrayList<>();
            try (
                var driver = TestDriverFactory.create(
                    driverContext,
                    new SequenceLongBlockSourceOperator(driverContext.blockFactory(), values, 100),
                    List.of(
                        new RowInTableLookupOperator(
                            driverContext.blockFactory(),
                            new RowInTableLookupOperator.Key[] { new RowInTableLookupOperator.Key("primes", primesBlock) },
                            new int[] { 0 }
                        )
                    ),
                    new PageConsumerOperator(page -> {
                        try {
                            BlockTestUtils.readInto(actualValues, page.getBlock(0));
                            BlockTestUtils.readInto(actualPrimeOrds, page.getBlock(1));
                        } finally {
                            page.releaseBlocks();
                        }
                    })
                )
            ) {
                OperatorTestCase.runDriver(driver);
            }

            assertThat(actualValues, equalTo(expectedValues));
            assertThat(actualPrimeOrds, equalTo(expectedPrimeOrds));
            assertDriverContext(driverContext);
        } finally {
            primesBlock.close();
        }
    }

    /**
     * Creates a {@link BigArrays} that tracks releases but doesn't throw circuit breaking exceptions.
     */
    private BigArrays bigArrays() {
        return new MockBigArrays(new MockPageCacheRecycler(Settings.EMPTY), new NoneCircuitBreakerService());
    }

    /**
     * A {@link DriverContext} that won't throw {@link CircuitBreakingException}.
     */
    protected final DriverContext driverContext() {
        var breaker = new MockBigArrays.LimitedBreaker("esql-test-breaker", ByteSizeValue.ofGb(1));
        return new DriverContext(bigArrays(), BlockFactory.getInstance(breaker, bigArrays()));
    }

    public static void assertDriverContext(DriverContext driverContext) {
        assertTrue(driverContext.isFinished());
        assertThat(driverContext.getSnapshot().releasables(), empty());
    }

    static LuceneOperator.Factory luceneOperatorFactory(IndexReader reader, List<LuceneSliceQueue.QueryAndTags> queryAndTags, int limit) {
        final ShardContext searchContext = new LuceneSourceOperatorTests.MockShardContext(reader, 0);
        return new LuceneSourceOperator.Factory(
            List.of(searchContext),
            ctx -> queryAndTags,
            randomFrom(DataPartitioning.values()),
            randomIntBetween(1, 10),
            randomPageSize(),
            limit,
            false // no scoring
        );
    }

    private MappedFieldType.BlockLoaderContext mockBlContext() {
        return new MappedFieldType.BlockLoaderContext() {
            @Override
            public String indexName() {
                throw new UnsupportedOperationException();
            }

            @Override
            public IndexSettings indexSettings() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MappedFieldType.FieldExtractPreference fieldExtractPreference() {
                return MappedFieldType.FieldExtractPreference.NONE;
            }

            @Override
            public SearchLookup lookup() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> sourcePaths(String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String parentField(String field) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FieldNamesFieldMapper.FieldNamesFieldType fieldNames() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
