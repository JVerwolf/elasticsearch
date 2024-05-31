/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script.examples.Development


import org.elasticsearch.search.rank.script.ScriptRankDoc


List retrieverResults = ctx.results;
List output = [];
for (int retrieverNum = 0; retrieverNum < retrieverResults.size(); ++retrieverNum) {
    for (ScriptRankDoc scriptRankDoc : retrieverResults.get(retrieverNum)) {
        int x = scriptRankDoc.getField("kw").length();
        int y = scriptRankDoc.getField("t").length();
        scriptRankDoc.setNewScore(x + y);
        output.add(scriptRankDoc);
    }
}
output.sort((ScriptRankDoc sd1, ScriptRankDoc sd2) -> {
    return sd1.newScore < sd2.newScore ? 1 : -1;
});
return output;