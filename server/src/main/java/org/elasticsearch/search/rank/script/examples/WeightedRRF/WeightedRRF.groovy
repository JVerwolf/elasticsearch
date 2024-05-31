/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script.examples.WeightedRRF


import org.elasticsearch.search.rank.script.RetrieverResults
import org.elasticsearch.search.rank.script.ScriptRankDoc

List retrieverResults = ctx.results;
def results = [:];
for (int retrieverNum = 0; retrieverNum < retrieverResults.size(); ++retrieverNum) {
    List retrieverResult = retrieverResults.get(retrieverNum);
    int rank = retrieverResult.size();
    for (ScriptRankDoc scriptRankDoc : retrieverResult) {
        results.compute(scriptRankDoc, (key, value) -> {
            def v = value;
            if (v == null) {
                v = scriptRankDoc;
                scriptRankDoc.setUserContext(["score": 0f]);
            }
            Map context = v.getUserContext();
            float score = context["score"];
            score += params.weights[retrieverNum] * (1.0 / (params.k + rank));
            v.setUserContext(["score": score]);
            return v;
        });
        --rank;
    }
}
def output = new ArrayList(results.values());
output.sort((ScriptRankDoc sd1, ScriptRankDoc sd2) -> {
    return sd1.getUserContext()["score"] < sd2.getUserContext()["score"] ? 1 : -1;
});
return output;