package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.List;
import java.util.Map;

public class DoucmentPostRetrieval {

    private final DocumentJoiner documentJoiner;

    public DoucmentPostRetrieval() {
        this.documentJoiner = new ConcatenationDocumentJoiner();
    }

    public DoucmentPostRetrieval(DocumentJoiner documentJoiner) {
        this.documentJoiner = documentJoiner;
    }

    public List<Document> dojoin(Map<Query, List<List<Document>>> documentMap) {
        return documentJoiner.join(documentMap);
    }
}