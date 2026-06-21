package com.ylcloud.service.rag.query;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class QueryPlan {
    private String original;
    private String normalized;
    private String rewrittenQuery;
    private String rewriteSource;
    private List<String> keywords = new ArrayList<>();
    private List<String> expandedQueries = new ArrayList<>();
    private String hydeDocument;
    private String stepBackQuery;
    private String intent;
    private List<String> warnings = new ArrayList<>();

    public List<String> retrievalQueries() {
        Set<String> queries = new LinkedHashSet<>();
        add(queries,original);
        add(queries,rewrittenQuery);
        add(queries,normalized);
        if(expandedQueries != null) {
            for(String query : expandedQueries) {
                add(queries,query);
            }
        }
        add(queries,stepBackQuery);
        return new ArrayList<>(queries);
    }

    private void add(Set<String> queries, String query) {
        if(query != null && !query.isBlank()) {
            queries.add(query.trim());
        }
    }
}
