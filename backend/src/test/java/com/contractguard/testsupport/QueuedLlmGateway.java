package com.contractguard.testsupport;

import com.contractguard.application.port.LlmGateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test double returning pre-programmed responses and recording every request. */
public class QueuedLlmGateway implements LlmGateway {

    private final Deque<String> responses = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    public QueuedLlmGateway enqueue(String... contents) {
        for (String content : contents) {
            responses.add(content);
        }
        return this;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (responses.isEmpty()) {
            throw new IllegalStateException("QueuedLlmGateway exhausted after " + requests.size() + " calls");
        }
        return new LlmResponse(responses.poll(), 10, 5);
    }

    public List<LlmRequest> requests() {
        return requests;
    }
}
