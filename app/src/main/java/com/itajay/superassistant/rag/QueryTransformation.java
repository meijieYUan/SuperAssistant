package com.itajay.superassistant.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;


public class QueryTransformation{
    public final CompressionQueryTransformer queryTransformer;
    public final RewriteQueryTransformer rewriteQueryTransformer;

    public QueryTransformation(ChatClient.Builder chatClientBuilder) {
        queryTransformer=CompressionQueryTransformer.
                builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        rewriteQueryTransformer=RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
    }

    /*
     *  根据历史对话进行重写查询
     * @param query
     * @return
     */
    public Query doCompress(Query query){
       return queryTransformer.transform(query);
    }

    /**
     *  重写抽象问题
     * @param query
     * @return
     */
    public Query doRewrite(Query query){
        return rewriteQueryTransformer.transform(query);
    }
}
