package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BM25 关键字召回：将 Query 文本交给 Lucene BM25 索引检索。
 */
@Component
public class Bm25DocumentRetriever implements DocumentRetriever {

    private final Bm25Index bm25Index;
    private final int topK;

    public Bm25DocumentRetriever(Bm25Index bm25Index,
                                 @Value("${rag.retrieval.bm25.top-k:10}") int topK) {
        this.bm25Index = bm25Index;
        this.topK = topK;
    }

    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null) {
            return List.of();
        }
        return bm25Index.search(query.text(), topK);
    }
}
