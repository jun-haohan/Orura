package com.junhaohan.knowledgeingestion.embedding.service;

import java.util.List;

public interface EmbeddingService {
    List<List<Float>> embed(List<String> texts);
}
