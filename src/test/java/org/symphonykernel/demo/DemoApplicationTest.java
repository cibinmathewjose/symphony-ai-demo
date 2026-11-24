package org.symphonykernel.demo;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.symphonykernel.ai.SpringAIHelper;
import org.symphonykernel.core.IAIClient;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "AZURE_OPENAI_ENDPOINT=https://demo-test.openai.azure.com", "AZURE_OPENAI_API_KEY=test-only",
    "AZURE_OPENAI_DEPLOYMENT=demo-test"
})
class DemoApplicationTest {
    @Autowired WebTestClient client;
    @Autowired Environment environment;
    @Autowired IAIClient aiClient;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "azureOpenAiChatModel", enforceOverride = true) ChatModel model;

    @Test
    void servesUiAndInventoryWithoutAuthentication() {
        route("Show inventory", "inventory");
        client.get().uri("/").exchange().expectStatus().isOk().expectBody(String.class)
                .value(html -> assertThat(html).contains("Symphony AI Demo", "POST /directchat/stream"));
        assertThat(chat(UUID.randomUUID(), "Show inventory")).contains("Night Cream", "P17");
    }

    @Test
    void pausesAndResumesReviewUsingTheSameChatEndpoint() {
        prepareReview();
        UUID session = UUID.randomUUID();
        assertThat(chat(session, "Start a stock review")).contains("AWAITING_INPUT", "Night Cream");
        assertThat(chat(session, "Approve review")).contains("Stock review completed", "Approve review");
    }

    @Test
    void streamsInventoryAsServerSentEvents() {
        route("Show inventory", "inventory");
        var events = stream(Map.of("sessionId", UUID.randomUUID(), "message", "Show inventory"));
        assertThat(events).anyMatch(event -> event.path("eventType").asText().equals("content")
            && event.path("content").asText().contains("Night Cream")
            && event.path("content").asText().contains("P17"));
    }

    @Test
    void generatesAndStreamsH2InventoryQueries() {
        assertThat(aiClient).isInstanceOf(SpringAIHelper.class);
        String question = "Which products have fewer than 15 units in stock?";
        route(question, "H2_DB");
        when(model.call(argThat((Prompt prompt) -> prompt != null
            && prompt.getSystemMessage().getText().startsWith("You are a query analysis assistant.")
            && prompt.getUserMessage().getText().contains(question)))).thenReturn(reply(question));
        when(model.call(argThat((Prompt prompt) -> isH2SqlPrompt(prompt, question))))
            .thenReturn(reply("SELECT SKU, PRODUCT, QUANTITY FROM PUBLIC.INVENTORY WHERE QUANTITY < 15 ORDER BY SKU"));
        assertThat(streamContent(UUID.randomUUID(), question))
                .contains("Night Cream", "Hand Balm").doesNotContain("Daily Cleanser");
        verify(model).call(argThat((Prompt prompt) -> isH2SqlPrompt(prompt, question)));
    }

    @Test
    void h2KnowledgeConnectionCanReadInventoryButCannotWrite() throws SQLException {
        String prefix = "symphony.db.demo-h2.";
        try (var connection = DriverManager.getConnection(environment.getRequiredProperty(prefix + "url"),
                environment.getRequiredProperty(prefix + "username"), environment.getRequiredProperty(prefix + "password"));
             var statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            try (var columns = connection.getMetaData().getColumns(null, "PUBLIC", "INVENTORY", "%")) {
                var names = new java.util.ArrayList<String>();
                while (columns.next()) {
                    names.add(columns.getString("COLUMN_NAME"));
                }
                assertThat(names).containsExactly("SKU", "PRODUCT", "QUANTITY");
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM PUBLIC.INVENTORY WHERE SKU = 'P17'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE PUBLIC.INVENTORY SET QUANTITY = 0 WHERE 1 = 0"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Not enough rights");
        }
    }

        @Test
        void collectsMissingProductDetailsAndInsertsOnlyAfterConfirmation() {
        UUID session = UUID.randomUUID();
        assertThat(product(Map.of("sessionId", session))).contains("AWAITING_INPUT", "What is the SKU?");
        assertThat(product(Map.of("sessionId", session, "answer", "demo_99"))).contains("What is the product name?");
        assertThat(product(Map.of("sessionId", session, "answer", "Test's Cream"))).contains("starting quantity");
        assertThat(product(Map.of("sessionId", session, "answer", "-1"))).contains("whole quantity");
        assertThat(product(Map.of("sessionId", session, "answer", "0"))).contains("Create this product");
        assertThat(jdbc.queryForList("SELECT SKU FROM INVENTORY WHERE SKU = ?", String.class, "DEMO_99")).isEmpty();
        assertThat(product(Map.of("sessionId", session, "answer", "Create product"))).contains("CREATED", "DEMO_99");
        assertThat(jdbc.queryForMap("SELECT PRODUCT, QUANTITY FROM INVENTORY WHERE SKU = ?", "DEMO_99"))
            .containsEntry("PRODUCT", "Test's Cream").containsEntry("QUANTITY", 0);
        assertThat(product(Map.of("sessionId", session, "answer", "Create product"))).contains("no pending product question");
        verifyNoInteractions(model);
        }

        @Test
        void skipsProvidedFieldsAndHandlesCancellationAndDuplicateSku() {
        UUID session = UUID.randomUUID();
        assertThat(product(Map.of("sessionId", session, "sku", "P17", "name", "Existing", "quantity", 2)))
            .contains("Create this product").doesNotContain("What is the SKU", "What is the product name", "starting quantity");
        assertThat(product(Map.of("sessionId", session, "answer", "Cancel"))).contains("CANCELLED");
        assertThat(product(Map.of("sessionId", session, "sku", "p17", "name", "Existing", "quantity", 2)))
            .contains("Create this product");
        assertThat(product(Map.of("sessionId", session, "answer", "Create product"))).contains("DUPLICATE_SKU");
        assertThat(jdbc.queryForObject("SELECT PRODUCT FROM INVENTORY WHERE SKU = 'P17'", String.class)).isEqualTo("Night Cream");
        verifyNoInteractions(model);
        }

        private String product(Map<String, Object> input) {
        return client.post().uri("/api/products/add").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(input).exchange().expectStatus().isOk()
            .returnResult(JsonNode.class).getResponseBody()
            .map(event -> event.path("message").asText() + " " + event.path("content").asText())
            .collectList().map(lines -> String.join("\n", lines)).block(Duration.ofSeconds(5));
        }

        private String streamContent(UUID session, String question) {
        return String.join("\n", stream(Map.of("sessionId", session, "message", question)).stream()
                .map(event -> event.path("content").asText()).toList());
    }

    @Test
    void streamsProgressAndResumesReviewWithSeparateContentChunks() {
        prepareReview();
        UUID session = UUID.randomUUID();
        var paused = stream(Map.of("session", session, "query", "Start a stock review"));
        assertThat(paused).extracting(event -> event.path("eventType").asText()).containsSubsequence("status", "content");
        assertThat(paused).anyMatch(event -> event.path("content").asText().contains("AWAITING_INPUT"));

        var resumed = stream(Map.of("session", session, "query", "Approve review"));
        assertThat(resumed).filteredOn(event -> "content".equals(event.path("eventType").asText()))
                .extracting(event -> event.path("content").asText())
                .containsSubsequence("Stock review completed. ", "Your decision: Approve review");
    }

    @Test
    void answersFromTheMatchingYamlFileInASubfolder() {
        route("What is the return window?", "return-policy");
        when(model.stream(argThat((Prompt prompt) -> prompt != null && prompt.getSystemMessage().getText().contains("within 30 days with a receipt"))))
            .thenReturn(Flux.just(reply("Unopened sample products can be returned within 30 days with a receipt.")));
        var events = stream(Map.of("sessionId", UUID.randomUUID(), "message", "What is the return window?"));
        assertThat(events).anyMatch(event -> "content".equals(event.path("eventType").asText())
                && event.path("content").asText().equals("Unopened sample products can be returned within 30 days with a receipt."));
    }

    @Test
    void rejectsInvalidStreamingInput() {
        client.post().uri("/directchat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(Map.of("message", "Show inventory"))
                .exchange().expectStatus().isBadRequest();
    }

    private void prepareReview() {
        route("Start a stock review", "review");
        when(model.call(argThat((Prompt prompt) -> prompt != null && !prompt.getSystemMessage().getText().startsWith("Analyze the user's question"))))
                .thenReturn(reply("NEW"));
        when(model.stream(argThat((Prompt prompt) -> prompt != null && prompt.getSystemMessage().getText().contains("reviewer decision")
                && prompt.getSystemMessage().getText().contains("Approve review"))))
                .thenReturn(Flux.just(reply("Stock review completed. "), reply("Your decision: Approve review")));
    }

    private void route(String question, String key) {
        when(model.call(argThat((Prompt prompt) -> {
            if (prompt == null) return false;
            String system = prompt.getSystemMessage().getText();
            return system.startsWith("Analyze the user's question " + question)
                    && system.contains("\"" + key + "\"") ;
        }))).thenReturn(reply(key));
    }

    private static boolean isH2SqlPrompt(Prompt prompt, String question) {
        if (prompt == null) return false;
        String instructions = prompt.getSystemMessage().getText();
        return instructions.contains("Use H2 SQL to query only PUBLIC.INVENTORY")
                && instructions.contains("TABLE: PUBLIC.INVENTORY") && instructions.contains("QUANTITY INTEGER")
                && prompt.getUserMessage().getText().contains(question);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private List<JsonNode> stream(Map<String, Object> input) {
        return client.post().uri("/directchat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(input).exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(JsonNode.class).getResponseBody().collectList().block(Duration.ofSeconds(5));
    }

    private String chat(UUID session, String message) {
        byte[] body = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionId", session, "message", message)).exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .expectBody().returnResult().getResponseBody();
        return new String(body, StandardCharsets.UTF_8);
    }
}