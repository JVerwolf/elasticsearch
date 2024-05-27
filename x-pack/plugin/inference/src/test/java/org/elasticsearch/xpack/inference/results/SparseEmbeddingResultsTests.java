/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.results;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractWireSerializingTestCase;
import org.elasticsearch.xpack.core.inference.results.SparseEmbedding;
import org.elasticsearch.xpack.core.inference.results.SparseEmbeddingResults;
import org.elasticsearch.xpack.core.ml.inference.results.TextExpansionResults;
import org.elasticsearch.xpack.core.ml.search.WeightedToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfig.DEFAULT_RESULTS_FIELD;
import static org.hamcrest.Matchers.is;

public class SparseEmbeddingResultsTests extends AbstractWireSerializingTestCase<SparseEmbeddingResults> {

    public static SparseEmbeddingResults createRandomResults() {
        int numEmbeddings = randomIntBetween(1, 10);
        int numTokens = randomIntBetween(0, 20);
        return createRandomResults(numEmbeddings, numTokens);
    }

    public static SparseEmbeddingResults createRandomResults(int numEmbeddings, int numTokens) {
        List<SparseEmbedding> embeddings = new ArrayList<>(numEmbeddings);

        for (int i = 0; i < numEmbeddings; i++) {
            embeddings.add(createRandomEmbedding(numTokens));
        }

        return new SparseEmbeddingResults(embeddings);
    }

    public static SparseEmbeddingResults createRandomResults(List<String> input) {
        List<SparseEmbedding> embeddings = new ArrayList<>(input.size());

        for (String s : input) {
            int numTokens = Strings.tokenizeToStringArray(s, " ").length;
            embeddings.add(createRandomEmbedding(numTokens));
        }

        return new SparseEmbeddingResults(embeddings);
    }

    private static SparseEmbedding createRandomEmbedding(int numTokens) {
        List<SparseEmbedding.WeightedToken> tokenList = new ArrayList<>(numTokens);
        for (int i = 0; i < numTokens; i++) {
            tokenList.add(new SparseEmbedding.WeightedToken(Integer.toString(i), (float) randomDoubleBetween(0.0, 5.0, false)));
        }

        return new SparseEmbedding(tokenList, randomBoolean());
    }

    @Override
    protected Writeable.Reader<SparseEmbeddingResults> instanceReader() {
        return SparseEmbeddingResults::new;
    }

    @Override
    protected SparseEmbeddingResults createTestInstance() {
        return createRandomResults();
    }

    @Override
    protected SparseEmbeddingResults mutateInstance(SparseEmbeddingResults instance) throws IOException {
        // if true we reduce the embeddings list by a random amount, if false we add an embedding to the list
        if (randomBoolean()) {
            // -1 to remove at least one item from the list
            int end = randomInt(instance.embeddings().size() - 1);
            return new SparseEmbeddingResults(instance.embeddings().subList(0, end));
        } else {
            List<SparseEmbedding> embeddings = new ArrayList<>(instance.embeddings());
            embeddings.add(createRandomEmbedding(randomIntBetween(0, 20)));
            return new SparseEmbeddingResults(embeddings);
        }
    }

    public void testToXContent_CreatesTheRightFormatForASingleEmbedding() throws IOException {
        var entity = createSparseResult(List.of(createEmbedding(List.of(new SparseEmbedding.WeightedToken("token", 0.1F)), false)));
        assertThat(entity.asMap(), is(buildExpectation(List.of(new EmbeddingExpectation(Map.of("token", 0.1F), false)))));
        String xContentResult = Strings.toString(entity, true, true);
        assertThat(xContentResult, is("""
            {
              "sparse_embedding" : [
                {
                  "is_truncated" : false,
                  "embedding" : {
                    "token" : 0.1
                  }
                }
              ]
            }"""));
    }

    public void testToXContent_CreatesTheRightFormatForMultipleEmbeddings() throws IOException {
        var entity = createSparseResult(
            List.of(
                new SparseEmbedding(
                    List.of(new SparseEmbedding.WeightedToken("token", 0.1F), new SparseEmbedding.WeightedToken("token2", 0.2F)),
                    false
                ),
                new SparseEmbedding(
                    List.of(new SparseEmbedding.WeightedToken("token3", 0.3F), new SparseEmbedding.WeightedToken("token4", 0.4F)),
                    false
                )
            )
        );
        assertThat(
            entity.asMap(),
            is(
                buildExpectation(
                    List.of(
                        new EmbeddingExpectation(Map.of("token", 0.1F, "token2", 0.2F), false),
                        new EmbeddingExpectation(Map.of("token3", 0.3F, "token4", 0.4F), false)
                    )
                )
            )
        );

        String xContentResult = Strings.toString(entity, true, true);
        assertThat(xContentResult, is("""
            {
              "sparse_embedding" : [
                {
                  "is_truncated" : false,
                  "embedding" : {
                    "token" : 0.1,
                    "token2" : 0.2
                  }
                },
                {
                  "is_truncated" : false,
                  "embedding" : {
                    "token3" : 0.3,
                    "token4" : 0.4
                  }
                }
              ]
            }"""));
    }

    public void testTransformToCoordinationFormat() {
        var results = createSparseResult(
            List.of(
                createEmbedding(List.of(new SparseEmbedding.WeightedToken("token", 0.1F)), false),
                createEmbedding(List.of(new SparseEmbedding.WeightedToken("token2", 0.2F)), true)
            )
        ).transformToCoordinationFormat();

        assertThat(
            results,
            is(
                List.of(
                    new TextExpansionResults(DEFAULT_RESULTS_FIELD, List.of(new WeightedToken("token", 0.1F)), false),
                    new TextExpansionResults(DEFAULT_RESULTS_FIELD, List.of(new WeightedToken("token2", 0.2F)), true)
                )
            )
        );
    }

    public record EmbeddingExpectation(Map<String, Float> tokens, boolean isTruncated) {}

    public static Map<String, Object> buildExpectation(List<EmbeddingExpectation> embeddings) {
        return Map.of(
            SparseEmbeddingResults.SPARSE_EMBEDDING,
            embeddings.stream()
                .map(embedding -> Map.of(SparseEmbedding.EMBEDDING, embedding.tokens, SparseEmbedding.IS_TRUNCATED, embedding.isTruncated))
                .toList()
        );
    }

    public static SparseEmbeddingResults createSparseResult(List<SparseEmbedding> embeddings) {
        return new SparseEmbeddingResults(embeddings);
    }

    public static SparseEmbedding createEmbedding(List<SparseEmbedding.WeightedToken> tokensList, boolean isTruncated) {
        return new SparseEmbedding(tokensList, isTruncated);
    }
}
