package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class DocumentRetrieval {
   public final DocumentRetriever documentRetriever;


    public DocumentRetrieval(VectorStore vectorStore) {
        this.documentRetriever = VectorStoreDocumentRetriever
                .builder()
                .vectorStore(vectorStore)
                .topK(5)
                .similarityThreshold(0.7)
                .build();
    }

    /**
     *  根据 querys 查询文档
     * @param query
     * @return
     */
    public List<Document> doRetrieve(Query query){
       return  documentRetriever.retrieve(query);
    }
}
