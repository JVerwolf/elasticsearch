/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.inference.ChunkedInferenceServiceResults;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.core.inference.results.ByteEmbedding;
import org.elasticsearch.xpack.core.inference.results.ChunkedSparseEmbeddingResults;
import org.elasticsearch.xpack.core.inference.results.ChunkedTextEmbeddingByteResults;
import org.elasticsearch.xpack.core.inference.results.ChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.inference.results.Embedding;
import org.elasticsearch.xpack.core.inference.results.EmbeddingChunk;
import org.elasticsearch.xpack.core.inference.results.EmbeddingResults;
import org.elasticsearch.xpack.core.inference.results.ErrorChunkedInferenceResults;
import org.elasticsearch.xpack.core.inference.results.FloatEmbedding;
import org.elasticsearch.xpack.core.inference.results.SparseEmbedding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * This class creates optimally sized batches of input strings
 * for batched processing splitting long strings into smaller
 * chunks. Multiple inputs may be fit into a single batch or
 * a single large input that has been chunked may spread over
 * multiple batches.
 *
 * The final aspect it to gather the responses from the batch
 * processing and map the results back to the original element
 * in the input list.
 */
public class EmbeddingRequestChunker {

    private static final Logger logger = LogManager.getLogger(EmbeddingRequestChunker.class);

    public static final int DEFAULT_WORDS_PER_CHUNK = 250;
    public static final int DEFAULT_CHUNK_OVERLAP = 100;

    private final List<BatchRequest> batchedRequests = new ArrayList<>();
    private final AtomicInteger resultCount = new AtomicInteger();
    private final int maxNumberOfInputsPerBatch;
    private final int wordsPerChunk;
    private final int chunkOverlap;

    private List<List<String>> chunkedInputs;
    private List<AtomicArray<List<? extends Embedding<?>>>> results;
    private AtomicArray<ErrorChunkedInferenceResults> errors;
    private ActionListener<List<ChunkedInferenceServiceResults>> finalListener;

    private AtomicReference<EmbeddingResults.EmbeddingType> firstResultType = new AtomicReference<>();

    public EmbeddingRequestChunker(List<String> inputs, int maxNumberOfInputsPerBatch) {
        this.maxNumberOfInputsPerBatch = maxNumberOfInputsPerBatch;
        this.wordsPerChunk = DEFAULT_WORDS_PER_CHUNK;
        this.chunkOverlap = DEFAULT_CHUNK_OVERLAP;
        splitIntoBatchedRequests(inputs);
    }

    public EmbeddingRequestChunker(List<String> inputs, int maxNumberOfInputsPerBatch, int wordsPerChunk, int chunkOverlap) {
        this.maxNumberOfInputsPerBatch = maxNumberOfInputsPerBatch;
        this.wordsPerChunk = wordsPerChunk;
        this.chunkOverlap = chunkOverlap;
        splitIntoBatchedRequests(inputs);
    }

    private void splitIntoBatchedRequests(List<String> inputs) {
        var chunker = new WordBoundaryChunker();
        chunkedInputs = new ArrayList<>(inputs.size());
        results = new ArrayList<>(inputs.size());
        errors = new AtomicArray<>(inputs.size());

        for (int i = 0; i < inputs.size(); i++) {
            var chunks = chunker.chunk(inputs.get(i), wordsPerChunk, chunkOverlap);
            int numberOfSubBatches = addToBatches(chunks, i);
            // size the results array with the expected number of request/responses
            results.add(new AtomicArray<>(numberOfSubBatches));
            chunkedInputs.add(chunks);
        }
    }

    private int addToBatches(List<String> chunks, int inputIndex) {
        BatchRequest lastBatch;
        if (batchedRequests.isEmpty()) {
            lastBatch = new BatchRequest(new ArrayList<>());
            batchedRequests.add(lastBatch);
        } else {
            lastBatch = batchedRequests.get(batchedRequests.size() - 1);
        }

        int freeSpace = maxNumberOfInputsPerBatch - lastBatch.size();
        assert freeSpace >= 0;

        // chunks may span multiple batches,
        // the chunkIndex keeps them ordered.
        int chunkIndex = 0;

        if (freeSpace > 0) {
            // use any free space in the previous batch before creating new batches
            int toAdd = Math.min(freeSpace, chunks.size());
            lastBatch.addSubBatch(new SubBatch(chunks.subList(0, toAdd), new SubBatchPositionsAndCount(inputIndex, chunkIndex++, toAdd)));
        }

        int start = freeSpace;
        while (start < chunks.size()) {
            int toAdd = Math.min(maxNumberOfInputsPerBatch, chunks.size() - start);
            var batch = new BatchRequest(new ArrayList<>());
            batch.addSubBatch(
                new SubBatch(chunks.subList(start, start + toAdd), new SubBatchPositionsAndCount(inputIndex, chunkIndex++, toAdd))
            );
            batchedRequests.add(batch);
            start += toAdd;
        }

        return chunkIndex;
    }

    /**
     * Returns a list of batched inputs and a ActionListener for each batch.
     * @param finalListener The listener to call once all the batches are processed
     * @return Batches and listeners
     */
    public List<BatchRequestAndListener> batchRequestsWithListeners(ActionListener<List<ChunkedInferenceServiceResults>> finalListener) {
        this.finalListener = finalListener;

        int numberOfRequests = batchedRequests.size();

        var requests = new ArrayList<BatchRequestAndListener>(numberOfRequests);
        for (var batch : batchedRequests) {
            requests.add(
                new BatchRequestAndListener(
                    batch,
                    new DebatchingListener(
                        batch.subBatches().stream().map(SubBatch::positions).collect(Collectors.toList()),
                        numberOfRequests
                    )
                )
            );
        }

        return requests;
    }

    /**
     * A grouping listener that calls the final listener only when
     * all responses have been received.
     * Long inputs that were split into chunks are reassembled and
     * returned as a single chunked response.
     * The listener knows where in the results array to insert the
     * response so that order is preserved.
     */
    private class DebatchingListener implements ActionListener<InferenceServiceResults> {

        private final List<SubBatchPositionsAndCount> positions;
        private final int totalNumberOfRequests;

        DebatchingListener(List<SubBatchPositionsAndCount> positions, int totalNumberOfRequests) {
            this.positions = positions;
            this.totalNumberOfRequests = totalNumberOfRequests;
        }

        @Override
        public void onResponse(InferenceServiceResults inferenceServiceResults) {
            if (inferenceServiceResults instanceof EmbeddingResults embeddingResults) {
                int numRequests = positions.stream().mapToInt(SubBatchPositionsAndCount::embeddingCount).sum();
                if (numRequests != embeddingResults.embeddings().size()) {
                    onFailure(
                        new ElasticsearchStatusException(
                            "Error the number of embedding responses [{}] does not equal the number of " + "requests [{}]",
                            RestStatus.BAD_REQUEST,
                            embeddingResults.embeddings().size(),
                            numRequests
                        )
                    );
                    return;
                }

                int start = 0;
                for (var pos : positions) {
                    results.get(pos.inputIndex())
                        .setOnce(pos.chunkIndex(), embeddingResults.embeddings().subList(start, start + pos.embeddingCount()));
                    start += pos.embeddingCount();
                }

                if (firstResultType.compareAndSet(null, embeddingResults.embeddingType()) == false) {
                    // firstResultType is set so check the latest matches the first
                    if (firstResultType.get().equals(embeddingResults.embeddingType()) == false) {
                        onFailure(
                            new ElasticsearchStatusException(
                                "The embedding response types are different. [{}] does not match the first response type [{}]",
                                RestStatus.BAD_REQUEST,
                                inferenceServiceResults.getClass().getSimpleName(),
                                firstResultType.get().matchedClass().getSimpleName()
                            )
                        );
                        return;
                    }
                }
            }

            if (resultCount.incrementAndGet() == totalNumberOfRequests) {
                sendResponse();
            }
        }

        @Override
        public void onFailure(Exception e) {
            var errorResult = new ErrorChunkedInferenceResults(e);
            for (var pos : positions) {
                errors.setOnce(pos.inputIndex(), errorResult);
            }

            if (resultCount.incrementAndGet() == totalNumberOfRequests) {
                sendResponse();
            }
        }

        private void sendResponse() {
            var response = new ArrayList<ChunkedInferenceServiceResults>(chunkedInputs.size());
            for (int i = 0; i < chunkedInputs.size(); i++) {
                if (errors.get(i) != null) {
                    response.add(errors.get(i));
                } else {
                    try {
                        response.add(mergeResults(firstResultType.get(), chunkedInputs.get(i), results.get(i)));
                    } catch (Exception e) {
                        response.add(new ErrorChunkedInferenceResults(e));
                    }
                }
            }

            finalListener.onResponse(response);
        }

        ChunkedInferenceServiceResults mergeResults(
            EmbeddingResults.EmbeddingType embeddingType,
            List<String> chunks,
            AtomicArray<List<? extends Embedding<?>>> embeddings
        ) {
            return switch (embeddingType) {
                case FLOAT -> mergeFloatResults(chunks, embeddings);
                case BYTE -> mergeByteResults(chunks, embeddings);
                case SPARSE -> mergeSparseResults(chunks, embeddings);
            };
        }

        private ChunkedTextEmbeddingFloatResults mergeFloatResults(
            List<String> chunks,
            AtomicArray<List<? extends Embedding<?>>> debatchedResults
        ) {
            var all = new ArrayList<FloatEmbedding>();
            for (int i = 0; i < debatchedResults.length(); i++) {
                var subBatch = debatchedResults.get(i);
                for (var result : subBatch) {
                    if (result instanceof FloatEmbedding fe) {
                        all.add(fe);
                    } else {
                        var message = "Unexpected embedding result type ["
                            + result.getClass().getSimpleName()
                            + "], expected a float embedding";
                        logger.error(message);
                        throw new IllegalStateException(message);
                    }
                }
            }

            assert chunks.size() == all.size();

            var embeddingChunks = new ArrayList<EmbeddingChunk<FloatEmbedding.FloatArrayWrapper>>();
            for (int i = 0; i < chunks.size(); i++) {
                embeddingChunks.add(new EmbeddingChunk<>(chunks.get(i), all.get(i)));
            }

            return new ChunkedTextEmbeddingFloatResults(embeddingChunks);
        }

        private ChunkedTextEmbeddingByteResults mergeByteResults(
            List<String> chunks,
            AtomicArray<List<? extends Embedding<?>>> debatchedResults
        ) {
            var all = new ArrayList<ByteEmbedding>();
            for (int i = 0; i < debatchedResults.length(); i++) {
                var subBatch = debatchedResults.get(i);
                for (var result : subBatch) {
                    if (result instanceof ByteEmbedding be) {
                        all.add(be);
                    } else {
                        var message = "Unexpected embedding result type ["
                            + result.getClass().getSimpleName()
                            + "], expected a byte embedding";
                        logger.error(message);
                        throw new IllegalStateException(message);
                    }
                }
            }

            assert chunks.size() == all.size();

            var embeddingChunks = new ArrayList<EmbeddingChunk<ByteEmbedding.ByteArrayWrapper>>();
            for (int i = 0; i < chunks.size(); i++) {
                embeddingChunks.add(new EmbeddingChunk<>(chunks.get(i), all.get(i)));
            }

            return new ChunkedTextEmbeddingByteResults(embeddingChunks, false);
        }

        private ChunkedSparseEmbeddingResults mergeSparseResults(
            List<String> chunks,
            AtomicArray<List<? extends Embedding<?>>> debatchedResults
        ) {
            var all = new ArrayList<SparseEmbedding>();
            for (int i = 0; i < debatchedResults.length(); i++) {
                var subBatch = debatchedResults.get(i);
                for (var result : subBatch) {
                    if (result instanceof SparseEmbedding se) {
                        all.add(se);
                    } else {
                        var message = "Unexpected embedding result type ["
                            + result.getClass().getSimpleName()
                            + "], expected a byte embedding";
                        logger.error(message);
                        throw new IllegalStateException(message);
                    }
                }
            }

            assert chunks.size() == all.size();

            var embeddingChunks = new ArrayList<EmbeddingChunk<SparseEmbedding.WeightedTokens>>();
            for (int i = 0; i < chunks.size(); i++) {
                embeddingChunks.add(new EmbeddingChunk<>(chunks.get(i), all.get(i)));
            }

            return ChunkedSparseEmbeddingResults.of(embeddingChunks);
        }
    }

    public record BatchRequest(List<SubBatch> subBatches) {
        public int size() {
            return subBatches.stream().mapToInt(SubBatch::size).sum();
        }

        public void addSubBatch(SubBatch sb) {
            subBatches.add(sb);
        }

        public List<String> inputs() {
            return subBatches.stream().flatMap(s -> s.requests().stream()).collect(Collectors.toList());
        }
    }

    public record BatchRequestAndListener(BatchRequest batch, ActionListener<InferenceServiceResults> listener) {

    }

    /**
     * Used for mapping batched requests back to the original input
     */
    record SubBatchPositionsAndCount(int inputIndex, int chunkIndex, int embeddingCount) {}

    record SubBatch(List<String> requests, SubBatchPositionsAndCount positions) {
        public int size() {
            return requests.size();
        }
    }
}
