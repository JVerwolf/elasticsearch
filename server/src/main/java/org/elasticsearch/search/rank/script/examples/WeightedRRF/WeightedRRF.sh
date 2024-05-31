#
# Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
# or more contributor license agreements. Licensed under the Elastic License
# 2.0 and the Server Side Public License, v 1; you may not use this file except
# in compliance with, at your election, the Elastic License 2.0 or the Server
# Side Public License, v 1.
#

# Demonstrates a weighted RRF implementation using the Script Reranker.
# The weights are passed in as params to the script: [0.7, 0.3]
curl -X GET -u elastic:password "localhost:9200/demo/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "retriever": {
    "script_rank": {
      "retrievers": [
        {
          "standard": {"query": {"term": {"kw": {"value": "two"}}}}
        },
        {
          "knn": {"field": "v", "query_vector": [9, 9], "k": 5, "num_candidates": 10}
        }
      ],
      "window_size": 10,
      "script": {
        "source": "List retrieverResults = ctx.results;\ndef results = [:];\nfor (int retrieverNum = 0; retrieverNum < retrieverResults.size(); ++retrieverNum) {\n    List retrieverResult = retrieverResults.get(retrieverNum);\n    int rank = retrieverResult.size();\n    for (ScriptRankDoc scriptRankDoc : retrieverResult) {\n        results.compute(scriptRankDoc, (key, value) -> {\n            def v = value;\n            if (v == null) {\n                v = scriptRankDoc;\n                scriptRankDoc.setUserContext([\"score\": 0f]);\n            }\n            Map context = v.getUserContext();\n            float score = context[\"score\"];\n            score += params.weights[retrieverNum] * (1.0 / (params.k + rank));\n            v.setUserContext([\"score\": score]);\n            return v;\n        });\n        --rank;\n    }\n}\ndef output = new ArrayList(results.values());\noutput.sort((ScriptRankDoc sd1, ScriptRankDoc sd2) -> {\n    return sd1.getUserContext()[\"score\"] < sd2.getUserContext()[\"score\"] ? 1 : -1;\n});\nreturn output;",
        "params": {
          "weights": [0.7, 0.3],
          "k": 20
        }
      }
    }
  },
  "_source": false,
  "fields": ["kw", "v"]
}
'
