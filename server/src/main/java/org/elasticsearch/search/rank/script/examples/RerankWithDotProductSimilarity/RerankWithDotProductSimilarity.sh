#
# Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
# or more contributor license agreements. Licensed under the Elastic License
# 2.0 and the Server Side Public License, v 1; you may not use this file except
# in compliance with, at your election, the Elastic License 2.0 or the Server
# Side Public License, v 1.
#

# Demonstrates re-ranking dot-product similarity on the top 10 docs returned by a Bool query.
curl -X GET -u elastic:password "localhost:9200/demo/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "retriever": {
    "script_rank": {
      "retrievers": [
        {
          "standard": {
            "query": {
              "bool": {
                "should": [
                  {"term": {"kw": {"value": "one"}}},
                  {"term": {"kw": {"value": "two"}}},
                  {"term": {"kw": {"value": "three"}}}
                ]
              }
            }
          }
        }
      ],
      "window_size": 10,
      "fields": ["v"],
      "script": {
        "source": "def output = [];\nfloat[] queryVector = new float[params.queryVector.size()];\nfor (int i = 0; i < queryVector.length; ++i) {\n    queryVector[i] = (float) params.queryVector[i];\n}\nqueryVector = VectorUtil.l2normalize(queryVector);\n\nfor (ScriptRankDoc scriptRankDoc : ctx.results[0]) {\n    def inputVector = scriptRankDoc.getField(\"v\");\n    float[] docVector = new float[inputVector.size()];\n    for (int i = 0; i < queryVector.length; ++i) {\n        docVector[i] = (float) inputVector[i];\n    }\n    docVector = VectorUtil.l2normalize(docVector);\n    float newScore = VectorUtil.dotProduct(queryVector, docVector);\n    scriptRankDoc.newScore = newScore;\n    output.add(scriptRankDoc);\n}\noutput.sort((ScriptRankDoc sd1, ScriptRankDoc sd2) -> { return sd1.getNewScore() < sd2.getNewScore() ? 1 : -1; });\nreturn output;",
        "params": {
          "queryVector": [2.0, 2.0]
        }
      }
    }
  },
  "_source": false,
  "fields": ["kw", "v"]
}
'
