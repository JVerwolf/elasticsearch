/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.action.search.SearchPhaseController;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.rank.context.QueryPhaseRankCoordinatorContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ScriptRankQueryPhaseCoordinatorContext extends QueryPhaseRankCoordinatorContext {

    private final ScriptService scriptService;
    private final Script script;

    private List<PriorityQueue<ScoreDoc>> queues = new ArrayList<>();

    public ScriptRankQueryPhaseCoordinatorContext(int windowSize, ScriptService scriptService, Script script) {
        super(windowSize);
        this.scriptService = scriptService;
        this.script = script;
    }

    public ScriptService getScriptService() {
        return scriptService;
    }

    public List<PriorityQueue<ScoreDoc>> getQueues() {
        return queues;
    }

    /**
     * @param querySearchResults Each QuerySearchResults contains an internal list of retriever results for a given query.
     *                           The outer list is per shard, inner is per retriever.
     * @param topDocStats
     * @return
     */
    @Override
    public ScoreDoc[] rankQueryPhaseResults(List<QuerySearchResult> querySearchResults, SearchPhaseController.TopDocsStats topDocStats) {
        for (QuerySearchResult querySearchResult : querySearchResults) {
            // this is the results for each retriever, the whole thing is for an individual shard.
            var topDocsList = ((ScriptRankShardResult) querySearchResult.getRankShardResult()).getTopDocsList();

            if (queues.isEmpty()) {
                for (int i = 0; i < topDocsList.size(); ++i) {
                    queues.add(new PriorityQueue<>(rankWindowSize + querySearchResult.from()) {
                        @Override
                        protected boolean lessThan(ScoreDoc a, ScoreDoc b) {
                            float score1 = a.score;
                            float score2 = b.score;
                            if (score1 != score2) {
                                return score1 < score2;
                            }
                            if (a.shardIndex != b.shardIndex) {
                                return a.shardIndex > b.shardIndex;
                            }
                            return a.doc > b.doc;
                        }
                    });
                }
            }

            // Each result in the topDocsList corresponds to a retriever. The whole thing is for 1 shard.
            for (int i = 0; i < topDocsList.size(); ++i) {
                for (ScoreDoc scoreDoc : topDocsList.get(i).scoreDocs) {
                    scoreDoc.shardIndex = querySearchResult.getShardIndex();
                    queues.get(i).insertWithOverflow(scoreDoc);
                }
            }
        }

        var seen = new HashMap<RankKey, ScoreDoc>();

        for (PriorityQueue<ScoreDoc> priorityQueue : queues) {
            for (ScoreDoc scoreDoc : priorityQueue) {
                seen.putIfAbsent(new RankKey(scoreDoc.doc, scoreDoc.shardIndex), scoreDoc);
            }
        }
        topDocStats.fetchHits = seen.size();

        return seen.values().toArray(ScoreDoc[]::new);
    }
}
