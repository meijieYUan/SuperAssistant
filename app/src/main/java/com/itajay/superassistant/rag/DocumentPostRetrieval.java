package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DocumentPostRetrieval {

    private final DocumentJoiner documentJoiner;

    public DocumentPostRetrieval() {
        this.documentJoiner = new ConcatenationDocumentJoiner();
    }

    public DocumentPostRetrieval(DocumentJoiner documentJoiner) {
        this.documentJoiner = documentJoiner;
    }

    public List<Document> dojoin(Map<Query, List<List<Document>>> documentMap) {
        return documentJoiner.join(documentMap);
    }
}
