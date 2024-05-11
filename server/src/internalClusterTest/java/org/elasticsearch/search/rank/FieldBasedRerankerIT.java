/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.search.SearchPhaseController;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.rank.context.QueryPhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.context.QueryPhaseRankShardContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankShardContext;
import org.elasticsearch.search.rank.feature.RankFeatureDoc;
import org.elasticsearch.search.rank.feature.RankFeatureResult;
import org.elasticsearch.search.rank.feature.RankFeatureShardResult;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.constantScoreQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailuresAndResponse;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertResponse;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.hasId;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.hasRank;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

@ESIntegTestCase.ClusterScope(minNumDataNodes = 3)
public class FieldBasedRerankerIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(FieldBasedRerankerPlugin.class);
    }

    public void testFieldBasedReranker() throws Exception {
        final String indexName = "test_index";
        final String rankFeatureField = "rankFeatureField";
        final String searchField = "searchField";
        final int rankWindowSize = 10;

        createIndex(indexName);
        indexRandom(
            true,
            prepareIndex(indexName).setId("1").setSource(rankFeatureField, 0.1, searchField, "A"),
            prepareIndex(indexName).setId("2").setSource(rankFeatureField, 0.2, searchField, "B"),
            prepareIndex(indexName).setId("3").setSource(rankFeatureField, 0.3, searchField, "C"),
            prepareIndex(indexName).setId("4").setSource(rankFeatureField, 0.4, searchField, "D"),
            prepareIndex(indexName).setId("5").setSource(rankFeatureField, 0.5, searchField, "E")
        );

        assertNoFailuresAndResponse(
            prepareSearch().setQuery(
                boolQuery().should(constantScoreQuery(matchQuery(searchField, "A")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "B")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "C")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "D")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "E")).boost(randomFloat()))
            )
                .setRankBuilder(new FieldBasedRankBuilder(rankWindowSize, rankFeatureField))
                .addFetchField(searchField)
                .setTrackTotalHits(true)
                .setAllowPartialSearchResults(true)
                .setSize(10),
            response -> {
                assertHitCount(response, 5L);
                int rank = 1;
                for (SearchHit searchHit : response.getHits().getHits()) {
                    assertThat(searchHit, hasId(String.valueOf(5 - (rank - 1))));
                    assertEquals(searchHit.getScore(), (0.5f - ((rank - 1) * 0.1f)), 1e-5f);
                    assertThat(searchHit, hasRank(rank));
                    assertNotNull(searchHit.getFields().get(searchField));
                    rank++;
                }
            }
        );
    }

    public void testFieldBasedRerankerPagination() throws Exception {
        final String indexName = "test_index";
        final String rankFeatureField = "rankFeatureField";
        final String searchField = "searchField";
        final int rankWindowSize = 10;

        createIndex(indexName);
        indexRandom(
            true,
            prepareIndex(indexName).setId("1").setSource(rankFeatureField, 0.1, searchField, "A"),
            prepareIndex(indexName).setId("2").setSource(rankFeatureField, 0.2, searchField, "B"),
            prepareIndex(indexName).setId("3").setSource(rankFeatureField, 0.3, searchField, "C"),
            prepareIndex(indexName).setId("4").setSource(rankFeatureField, 0.4, searchField, "D"),
            prepareIndex(indexName).setId("5").setSource(rankFeatureField, 0.5, searchField, "E")
        );

        assertResponse(
            prepareSearch().setQuery(
                boolQuery().should(constantScoreQuery(matchQuery(searchField, "A")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "B")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "C")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "D")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "E")).boost(randomFloat()))
            )
                .setRankBuilder(new FieldBasedRankBuilder(rankWindowSize, rankFeatureField))
                .addFetchField(searchField)
                .setTrackTotalHits(true)
                .setAllowPartialSearchResults(true)
                .setSize(2)
                .setFrom(2),
            response -> {
                assertHitCount(response, 5L);
                int rank = 3;
                for (SearchHit searchHit : response.getHits().getHits()) {
                    assertThat(searchHit, hasId(String.valueOf(5 - (rank - 1))));
                    assertEquals(searchHit.getScore(), (0.5f - ((rank - 1) * 0.1f)), 1e-5f);
                    assertThat(searchHit, hasRank(rank));
                    assertNotNull(searchHit.getFields().get(searchField));
                    rank++;
                }
            }
        );
    }

    public void testFieldBasedRerankerPaginationOutsideOfBounds() throws Exception {
        final String indexName = "test_index";
        final String rankFeatureField = "rankFeatureField";
        final String searchField = "searchField";
        final int rankWindowSize = 10;

        createIndex(indexName);
        indexRandom(
            true,
            prepareIndex(indexName).setId("1").setSource(rankFeatureField, 0.1, searchField, "A"),
            prepareIndex(indexName).setId("2").setSource(rankFeatureField, 0.2, searchField, "B"),
            prepareIndex(indexName).setId("3").setSource(rankFeatureField, 0.3, searchField, "C"),
            prepareIndex(indexName).setId("4").setSource(rankFeatureField, 0.4, searchField, "D"),
            prepareIndex(indexName).setId("5").setSource(rankFeatureField, 0.5, searchField, "E")
        );

        assertNoFailuresAndResponse(
            prepareSearch().setQuery(
                boolQuery().should(constantScoreQuery(matchQuery(searchField, "A")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "B")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "C")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "D")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "E")).boost(randomFloat()))
            )
                .setRankBuilder(new FieldBasedRankBuilder(rankWindowSize, rankFeatureField))
                .addFetchField(searchField)
                .setTrackTotalHits(true)
                .setAllowPartialSearchResults(true)
                .setSize(2)
                .setFrom(10),
            response -> {
                assertHitCount(response, 5L);
                assertEquals(0, response.getHits().getHits().length);
            }
        );
    }

    public void testFieldBasedRerankerNoMatchingDocs() throws Exception {
        final String indexName = "test_index";
        final String rankFeatureField = "rankFeatureField";
        final String searchField = "searchField";
        final int rankWindowSize = 10;

        createIndex(indexName);
        indexRandom(
            true,
            prepareIndex(indexName).setId("1").setSource(rankFeatureField, 0.1, searchField, "A"),
            prepareIndex(indexName).setId("2").setSource(rankFeatureField, 0.2, searchField, "B"),
            prepareIndex(indexName).setId("3").setSource(rankFeatureField, 0.3, searchField, "C"),
            prepareIndex(indexName).setId("4").setSource(rankFeatureField, 0.4, searchField, "D"),
            prepareIndex(indexName).setId("5").setSource(rankFeatureField, 0.5, searchField, "E")
        );

        assertNoFailuresAndResponse(
            prepareSearch().setQuery(boolQuery().should(constantScoreQuery(matchQuery(searchField, "F")).boost(randomFloat())))
                .setRankBuilder(new FieldBasedRankBuilder(rankWindowSize, rankFeatureField))
                .addFetchField(searchField)
                .setTrackTotalHits(true)
                .setAllowPartialSearchResults(true)
                .setSize(10),
            response -> {
                assertHitCount(response, 0L);
            }
        );
    }

    public void testThrowingRankBuilderAllContextsAreClosed() throws Exception {
        final String indexName = "test_index";
        final String rankFeatureField = "rankFeatureField";
        final String searchField = "searchField";
        final int rankWindowSize = 10;

        createIndex(indexName);
        indexRandom(
            true,
            prepareIndex(indexName).setId("1").setSource(rankFeatureField, 0.1, searchField, "A"),
            prepareIndex(indexName).setId("2").setSource(rankFeatureField, 0.2, searchField, "B"),
            prepareIndex(indexName).setId("3").setSource(rankFeatureField, 0.3, searchField, "C"),
            prepareIndex(indexName).setId("4").setSource(rankFeatureField, 0.4, searchField, "D"),
            prepareIndex(indexName).setId("5").setSource(rankFeatureField, 0.5, searchField, "E")
        );

        assertResponse(
            prepareSearch().setQuery(
                boolQuery().should(constantScoreQuery(matchQuery(searchField, "A")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "B")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "C")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "D")).boost(randomFloat()))
                    .should(constantScoreQuery(matchQuery(searchField, "E")).boost(randomFloat()))
            )
                .setRankBuilder(new ThrowingRankBuilder(rankWindowSize, rankFeatureField))
                .addFetchField(searchField)
                .setTrackTotalHits(true)
                .setAllowPartialSearchResults(true)
                .setSize(10),
            response -> {
                assertTrue(response.getFailedShards() > 0);
                assertTrue(
                    Arrays.stream(response.getShardFailures())
                        .allMatch(failure -> failure.getCause().getMessage().equals("This rank builder throws an exception"))
                );
                assertHitCount(response, 5);
                assertTrue(response.getHits().getHits().length == 0);
            }
        );
    }

    public static class FieldBasedRankBuilder extends RankBuilder {

        public static final ParseField FIELD_FIELD = new ParseField("field");
        static final ConstructingObjectParser<FieldBasedRankBuilder, Void> PARSER = new ConstructingObjectParser<>(
            "field-based-rank",
            args -> {
                int rankWindowSize = args[0] == null ? DEFAULT_RANK_WINDOW_SIZE : (int) args[0];
                String field = (String) args[1];
                if (field == null || field.isEmpty()) {
                    throw new IllegalArgumentException("Field cannot be null or empty");
                }
                return new FieldBasedRankBuilder(rankWindowSize, field);
            }
        );

        static {
            PARSER.declareInt(optionalConstructorArg(), RANK_WINDOW_SIZE_FIELD);
            PARSER.declareString(constructorArg(), FIELD_FIELD);
        }

        protected final String field;

        public static FieldBasedRankBuilder fromXContent(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }

        public FieldBasedRankBuilder(final int rankWindowSize, final String field) {
            super(rankWindowSize);
            this.field = field;
        }

        public FieldBasedRankBuilder(StreamInput in) throws IOException {
            super(in);
            this.field = in.readString();
        }

        @Override
        protected void doWriteTo(StreamOutput out) throws IOException {
            out.writeString(field);
        }

        @Override
        protected void doXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(FIELD_FIELD.getPreferredName(), field);
        }

        @Override
        public boolean isCompoundBuilder() {
            return false;
        }

        @Override
        public QueryPhaseRankShardContext buildQueryPhaseShardContext(List<Query> queries, int from) {
            return new QueryPhaseRankShardContext(queries, rankWindowSize()) {
                @Override
                public RankShardResult combineQueryPhaseResults(List<TopDocs> rankResults) {
                    Map<Integer, RankFeatureDoc> rankDocs = new HashMap<>();
                    rankResults.forEach(topDocs -> {
                        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                            rankDocs.compute(scoreDoc.doc, (key, value) -> {
                                if (value == null) {
                                    return new RankFeatureDoc(scoreDoc.doc, scoreDoc.score, scoreDoc.shardIndex);
                                } else {
                                    value.score = Math.max(scoreDoc.score, rankDocs.get(scoreDoc.doc).score);
                                    return value;
                                }
                            });
                        }
                    });
                    RankFeatureDoc[] sortedResults = rankDocs.values().toArray(RankFeatureDoc[]::new);
                    Arrays.sort(sortedResults, (o1, o2) -> Float.compare(o2.score, o1.score));
                    return new RankFeatureShardResult(sortedResults);
                }
            };
        }

        @Override
        public QueryPhaseRankCoordinatorContext buildQueryPhaseCoordinatorContext(int size, int from) {
            return new QueryPhaseRankCoordinatorContext(rankWindowSize()) {
                @Override
                public ScoreDoc[] rankQueryPhaseResults(
                    List<QuerySearchResult> querySearchResults,
                    SearchPhaseController.TopDocsStats topDocStats
                ) {
                    List<RankFeatureDoc> rankDocs = new ArrayList<>();
                    for (int i = 0; i < querySearchResults.size(); i++) {
                        QuerySearchResult querySearchResult = querySearchResults.get(i);
                        RankFeatureShardResult shardResult = (RankFeatureShardResult) querySearchResult.getRankShardResult();
                        for (RankFeatureDoc frd : shardResult.rankFeatureDocs) {
                            frd.shardIndex = i;
                            rankDocs.add(frd);
                        }
                    }
                    // no support for sort field atm
                    // should pass needed info to make use of org.elasticsearch.action.search.SearchPhaseController.sortDocs?
                    rankDocs.sort(Comparator.comparing((RankFeatureDoc doc) -> doc.score).reversed());
                    RankFeatureDoc[] topResults = rankDocs.stream().limit(rankWindowSize).toArray(RankFeatureDoc[]::new);

                    assert topDocStats.fetchHits == 0;
                    topDocStats.fetchHits = topResults.length;

                    return topResults;
                }
            };
        }

        @Override
        public RankFeaturePhaseRankShardContext buildRankFeaturePhaseShardContext() {
            return new RankFeaturePhaseRankShardContext(field) {
                @Override
                public RankShardResult buildRankFeatureShardResult(SearchHits hits, int shardId) {
                    try {
                        RankFeatureDoc[] rankFeatureDocs = new RankFeatureDoc[hits.getHits().length];
                        for (int i = 0; i < hits.getHits().length; i++) {
                            rankFeatureDocs[i] = new RankFeatureDoc(hits.getHits()[i].docId(), hits.getHits()[i].getScore(), shardId);
                            rankFeatureDocs[i].featureData(hits.getHits()[i].field(field).getValue().toString());
                        }
                        return new RankFeatureShardResult(rankFeatureDocs);
                    } catch (Exception ex) {
                        throw ex;
                    }
                }
            };
        }

        @Override
        public RankFeaturePhaseRankCoordinatorContext buildRankFeaturePhaseCoordinatorContext(int size, int from) {
            return new RankFeaturePhaseRankCoordinatorContext(size, from, rankWindowSize()) {
                @Override
                public void rankGlobalResults(List<RankFeatureResult> rankSearchResults, Consumer<ScoreDoc[]> onFinish) {
                    RankFeatureDoc[] featureDocs = extractFeatures(rankSearchResults);
                    for (RankFeatureDoc featureDoc : featureDocs) {
                        featureDoc.score = Float.parseFloat(featureDoc.featureData);
                    }
                    Arrays.sort(featureDocs, Comparator.comparing((RankFeatureDoc doc) -> doc.score).reversed());
                    RankFeatureDoc[] topResults = new RankFeatureDoc[Math.max(0, Math.min(size, featureDocs.length - from))];
                    for (int rank = 0; rank < topResults.length; ++rank) {
                        topResults[rank] = featureDocs[from + rank];
                        topResults[rank].rank = from + rank + 1;
                    }
                    // and call the parent onFinish consumer with the final `ScoreDoc[]` results.
                    onFinish.accept(topResults);
                }

                private RankFeatureDoc[] extractFeatures(List<RankFeatureResult> rankSearchResults) {
                    List<RankFeatureDoc> docFeatures = new ArrayList<>();
                    for (RankFeatureResult rankFeatureResult : rankSearchResults) {
                        RankFeatureShardResult shardResult = rankFeatureResult.shardResult();
                        docFeatures.addAll(Arrays.stream(shardResult.rankFeatureDocs).toList());
                    }
                    return docFeatures.toArray(new RankFeatureDoc[0]);
                }
            };
        }

        @Override
        protected boolean doEquals(RankBuilder other) {
            return other instanceof FieldBasedRankBuilder && Objects.equals(field, ((FieldBasedRankBuilder) other).field);
        }

        @Override
        protected int doHashCode() {
            return Objects.hash(field);
        }

        @Override
        public String getWriteableName() {
            return "field-based-rank";
        }

        @Override
        public TransportVersion getMinimalSupportedVersion() {
            return TransportVersion.current();
        }
    }

    public static class ThrowingRankBuilder extends FieldBasedRankBuilder {

        public static final ParseField FIELD_FIELD = new ParseField("field");
        static final ConstructingObjectParser<ThrowingRankBuilder, Void> PARSER = new ConstructingObjectParser<>("throwing-rank", args -> {
            int rankWindowSize = args[0] == null ? DEFAULT_RANK_WINDOW_SIZE : (int) args[0];
            String field = (String) args[1];
            if (field == null || field.isEmpty()) {
                throw new IllegalArgumentException("Field cannot be null or empty");
            }
            return new ThrowingRankBuilder(rankWindowSize, field);
        });

        static {
            PARSER.declareInt(optionalConstructorArg(), RANK_WINDOW_SIZE_FIELD);
            PARSER.declareString(constructorArg(), FIELD_FIELD);
        }

        public static FieldBasedRankBuilder fromXContent(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }

        public ThrowingRankBuilder(final int rankWindowSize, final String field) {
            super(rankWindowSize, field);
        }

        public ThrowingRankBuilder(StreamInput in) throws IOException {
            super(in);
        }

        @Override
        public String getWriteableName() {
            return "throwing-rank";
        }

        @Override
        public RankFeaturePhaseRankShardContext buildRankFeaturePhaseShardContext() {
            return new RankFeaturePhaseRankShardContext(field) {
                @Override
                public RankShardResult buildRankFeatureShardResult(SearchHits hits, int shardId) {
                    throw new IllegalArgumentException("This rank builder throws an exception");
                }
            };
        }
    }

    public static class FieldBasedRerankerPlugin extends Plugin implements SearchPlugin {

        private static final String FIELD_BASED_RANK_BUILDER_NAME = "field-based-rank";
        private static final String THROWING_RANK_BUILDER_NAME = "throwing-rank";

        @Override
        public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
            return List.of(
                new NamedWriteableRegistry.Entry(RankBuilder.class, FIELD_BASED_RANK_BUILDER_NAME, FieldBasedRankBuilder::new),
                new NamedWriteableRegistry.Entry(RankBuilder.class, THROWING_RANK_BUILDER_NAME, ThrowingRankBuilder::new),
                new NamedWriteableRegistry.Entry(RankShardResult.class, "rank-feature-shard", RankFeatureShardResult::new)
            );
        }

        @Override
        public List<NamedXContentRegistry.Entry> getNamedXContent() {
            return List.of(
                new NamedXContentRegistry.Entry(
                    RankBuilder.class,
                    new ParseField(FIELD_BASED_RANK_BUILDER_NAME),
                    FieldBasedRankBuilder::fromXContent
                ),
                new NamedXContentRegistry.Entry(
                    RankBuilder.class,
                    new ParseField(THROWING_RANK_BUILDER_NAME),
                    ThrowingRankBuilder::fromXContent
                )
            );
        }
    }
}
