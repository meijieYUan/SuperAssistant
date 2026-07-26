package com.itajay.superassistant.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;

import java.util.List;

public class QueryExpansion {
    public final MultiQueryExpander multiQueryExpander;

    public QueryExpansion(ChatClient.Builder chatClientBuilder){
        multiQueryExpander= MultiQueryExpander
                .builder()
                .chatClientBuilder(chatClientBuilder)
                .includeOriginal(true)
                .numberOfQueries(3)
                .build();
    }

    /**
     *  对原始查询进行问题拓展为多个问题
     * @param query
     * @return
     */
    public List<Query> doExpand(Query query){
       return  multiQueryExpander.expand(query);
    }
}
