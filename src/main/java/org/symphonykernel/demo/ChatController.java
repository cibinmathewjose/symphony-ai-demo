package org.symphonykernel.demo;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.symphonykernel.ChatRequest;
import org.symphonykernel.ai.KnowledgeGraphBuilder;
import org.symphonykernel.steps.HumanInLoopStateStore;
import org.symphonykernel.steps.WorkflowCheckpointStore;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class ChatController {
    record ChatInput(@JsonAlias("session") UUID sessionId, @JsonAlias("query") String message) { }
    record ProductInput(UUID sessionId, String sku, String name, JsonNode quantity, String answer) { }

    private final KnowledgeGraphBuilder graph;
    private final ObjectMapper mapper;
    private final HumanInLoopStateStore pending;
    private final WorkflowCheckpointStore checkpoints;
    private final Set<UUID> productRequests = ConcurrentHashMap.newKeySet();

    ChatController(KnowledgeGraphBuilder graph, ObjectMapper mapper, HumanInLoopStateStore pending,
            WorkflowCheckpointStore checkpoints) {
        this.graph = graph;
        this.mapper = mapper;
        this.pending = pending;
        this.checkpoints = checkpoints;
    }

    @PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_NDJSON_VALUE)
    Flux<JsonNode> chat(@RequestBody ChatInput input) {
        return streamEvents(input);
        }

        @PostMapping(path = "/directchat/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<ServerSentEvent<JsonNode>> directChatStream(@RequestBody ChatInput input) {
        return streamEvents(input).map(event -> ServerSentEvent.builder(event).build());
        }

    @PostMapping(path = "/api/products/add", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<JsonNode>> addProduct(@RequestBody ProductInput input) {
        if (input.sessionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a sessionId.");
        }
        return Flux.defer(() -> {
            if (!productRequests.add(input.sessionId())) {
                return Flux.just(errorEvent("A product request is already running for this session."));
            }
            return Flux.defer(() -> productEvents(input)).doFinally(signal -> productRequests.remove(input.sessionId()));
        }).subscribeOn(Schedulers.boundedElastic()).map(event -> ServerSentEvent.builder(event).build());
    }

    private Flux<JsonNode> productEvents(ProductInput input) {
        String session = "product:" + input.sessionId();
        String pendingId = pending.getPendingRequestIdBySession(session);
        ChatRequest request = new ChatRequest();
        request.setUser(ProductWorkflow.USER);
        request.setSession(session);
        try {
            if (input.answer() != null) {
                var checkpoint = pendingId == null ? null : checkpoints.load(pendingId);
                if (checkpoint == null || !"add-product".equals(checkpoint.getKnowledgeName())) {
                    return Flux.just(errorEvent("There is no pending product question. Start an Add product request."));
                }
                if (input.sku() != null || input.name() != null || input.quantity() != null) {
                    return Flux.just(errorEvent("Send only sessionId and answer when resuming."));
                }
                request.setQuery(ProductWorkflow.validate(checkpoint.getWaitingStepKey(), input.answer()));
            } else {
                if (pendingId != null) {
                    return Flux.just(errorEvent("Answer the pending product question before starting another product."));
                }
                var payload = mapper.createObjectNode();
                if (input.sku() != null) payload.put("sku", ProductWorkflow.validate("sku", input.sku()));
                if (input.name() != null) payload.put("name", ProductWorkflow.validate("name", input.name()));
                if (input.quantity() != null && !input.quantity().isNull()) {
                    if (!input.quantity().isIntegralNumber()) throw new IllegalArgumentException("Send quantity as a whole JSON number.");
                    payload.put("quantity", ProductWorkflow.validate("quantity", input.quantity().asText()));
                }
                request.setPayload(payload.toString());
                request.setQuery("Key:add-product");
            }
        } catch (IllegalArgumentException ex) {
            return Flux.just(errorEvent(ex.getMessage()));
        }
        if (input.answer() == null) {
            return graphEvents(Mono.fromCallable(() -> graph.prepareContext(request)).flatMapMany(graph::streamResponse));
        }
        return graphEvents(Flux.defer(() -> graph.streamProcess(request)));
    }

        private Flux<JsonNode> streamEvents(ChatInput input) {
        if (input.sessionId() == null || input.message() == null || input.message().isBlank()
                || input.message().length() > 2000 || input.message().stripLeading().startsWith("Key:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a session ID and a message up to 2,000 characters.");
        }
        ChatRequest request = new ChatRequest();
        request.setUser("demo");
        request.setSession(input.sessionId().toString());
        request.setQuery(input.message().strip());
        return graphEvents(Flux.defer(() -> graph.streamProcess(request)));
        }

    private Flux<JsonNode> graphEvents(Flux<String> events) {
        return events.subscribeOn(Schedulers.boundedElastic())
                .map(this::readEvent)
            .onErrorResume(error -> Flux.just(errorEvent("The demo could not complete the request. Try starting a new chat.")));
        }

        private JsonNode errorEvent(String message) {
        return mapper.createObjectNode().put("eventType", "error").put("message", message);
    }

    private JsonNode readEvent(String chunk) {
        try {
            return mapper.readTree(chunk);
        } catch (JsonProcessingException ex) {
            return mapper.createObjectNode().put("eventType", "content").put("content", chunk);
        }
    }
}