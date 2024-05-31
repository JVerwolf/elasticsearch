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
        }
      ],
      "window_size": 10,
      "fields": ["kw","t"],
      "script": {
        "source": "List retrieverResults = ctx.results;\nList output = [];\nfor (int retrieverNum = 0; retrieverNum < retrieverResults.size(); ++retrieverNum) {\n    for (ScriptRankDoc scriptRankDoc : retrieverResults.get(retrieverNum)) {\n        int x = scriptRankDoc.getField(\"kw\").length();\n        int y = scriptRankDoc.getField(\"t\").length();\n        scriptRankDoc.setNewScore(x + y);\n        output.add(scriptRankDoc);\n    }\n}\noutput.sort((ScriptRankDoc sd1, ScriptRankDoc sd2) -> {\n    return sd1.newScore < sd2.newScore ? 1 : -1;\n});\nreturn output;",
        "params": {
        }
      }
    }
  },
  "fields": ["kw","t"],
  "_source": false
}
'
