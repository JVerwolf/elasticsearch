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
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.rank.context.QueryPhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.feature.RankFeatureDoc;
import org.elasticsearch.search.rank.feature.RankFeatureResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScriptRankRankPhaseCoordinatorContext extends RankFeaturePhaseRankCoordinatorContext {

    private final Script script;
    private final Map<RankKey, RankFeatureDoc> lookup = new HashMap<>();
    private final List<String> fieldNames;

    public ScriptRankRankPhaseCoordinatorContext(int size, int from, int windowSize, Script script, List<String> fieldNames) {
        super(size, from, windowSize);
        this.script = script;
        this.fieldNames = fieldNames;
    }

    /**
     * This dedupes results prior to running queries that could be slow, ex. ML.
     */
    @Override
    public void rankGlobalResults(
        QueryPhaseRankCoordinatorContext queryPhaseRankCoordinatorContext,
        List<RankFeatureResult> phaseResultsPerShard,
        ActionListener<RankFeatureDoc[]> onFinish
    ) {
        for (var rankFeatureResult : phaseResultsPerShard) {
            for (var rankFeatureDoc : rankFeatureResult.shardResult().rankFeatureDocs) {
                lookup.put(new RankKey(rankFeatureDoc.doc, rankFeatureDoc.shardIndex), rankFeatureDoc);
            }
        }
        onFinish.onResponse(lookup.values().toArray(RankFeatureDoc[]::new));
        // TODO fix window size
    }

    @Override
    public RankFeatureDoc[] rankOnResponse(
        QueryPhaseRankCoordinatorContext queryPhaseRankCoordinatorContext,
        List<RankFeatureResult> phaseResultsPerShard,
        RankFeatureDoc[] rankFeatureDocs // TODO we aren't using these and their scores
    ) {
        // TODO these need to return multiple iterators on the same object
        var queues = ((ScriptRankQueryPhaseCoordinatorContext) queryPhaseRankCoordinatorContext).getQueues();
        List<List<ScriptRankDoc>> allRetrieverResults = new ArrayList<>(queues.size());
        for (PriorityQueue<ScoreDoc> queue : queues) {
            List<ScriptRankDoc> currentRetrieverResults = new ArrayList<>();
            while (queue.size() != 0) {
                ScoreDoc scoreDoc = queue.pop();
                var rankFeatureDoc = lookup.get(new RankKey(scoreDoc.doc, scoreDoc.shardIndex));
                if (rankFeatureDoc != null) {
                    currentRetrieverResults.add(new ScriptRankDoc(scoreDoc, new HashMap<>(1) {
                        {
                            for (int i = 0; i < fieldNames.size(); ++i) {
                                put(fieldNames.get(i), rankFeatureDoc.fieldValues.get(i)); // TODO add protection here
                            }
                        }
                    }));
                } else {
                    currentRetrieverResults.add(new ScriptRankDoc(scoreDoc, new HashMap<>(0)));
                }
            }
            allRetrieverResults.add(currentRetrieverResults);
        }
        var scriptService = ((ScriptRankQueryPhaseCoordinatorContext) queryPhaseRankCoordinatorContext).getScriptService();
        RankScript.Factory factory = scriptService.compile(script, RankScript.CONTEXT);
        RankScript rankScript = factory.newInstance(script.getParams());

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("results", allRetrieverResults);
        ctx.put("size", size);
        ctx.put("from", from);
        ctx.put("windowSize", rankWindowSize);

        // TODO change this to return an Iterable<ScriptRankDoc>
        List<ScriptRankDoc> scriptResult = rankScript.execute(ctx);

        List<RankFeatureDoc> results = new ArrayList<>(scriptResult.size());
        for (ScriptRankDoc scriptRankDoc : scriptResult) {
            ScoreDoc scoreDoc = scriptRankDoc.getScoreDoc();
            results.add(new RankFeatureDoc(scoreDoc.doc, scriptRankDoc.getNewScore(), scoreDoc.shardIndex));
        }
        return results.toArray(RankFeatureDoc[]::new);
    }
}
