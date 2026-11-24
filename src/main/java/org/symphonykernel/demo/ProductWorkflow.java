package org.symphonykernel.demo;

import java.util.Locale;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.symphonykernel.ChatResponse;
import org.symphonykernel.ExecutionContext;
import org.symphonykernel.Knowledge;
import org.symphonykernel.QueryType;
import org.symphonykernel.ai.KnowledgeExecuterFactory;
import org.symphonykernel.core.IStep;
import org.symphonykernel.steps.BaseStep;

import com.fasterxml.jackson.databind.JsonNode;

/** Local, deterministic steps for the product YAML; no model is allowed to write inventory. */
@Component
class ProductWorkflow extends BaseStep {
    static final String USER = "demo-product-endpoint";
    static final String CHECK = "product-field-check";
    static final String CREATE = "product-create";
    private final JdbcTemplate jdbc;

    ProductWorkflow(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChatResponse getResponse(ExecutionContext ctx) {
        if (ctx.getRequest() == null || !USER.equals(ctx.getRequest().getUser()) || ctx.getCurrentFlowItem() == null) {
            throw new IllegalArgumentException("Use the Add product endpoint to run this workflow.");
        }
        JsonNode input = ctx.getVariables();
        var row = objectMapper.createObjectNode();
        if (CHECK.equals(ctx.getKnowledge().getName())) {
            for (String field : new String[] {"sku", "name", "quantity"}) {
                row.put(field, input.path(field).asText("").isBlank());
            }
        } else if ("Create product".equals(input.path("confirmation").asText())) {
            String sku = validate("sku", input.path("sku").asText(""));
            String name = validate("name", input.path("name").asText(""));
            int quantity = Integer.parseInt(validate("quantity", input.path("quantity").asText("")));
            try {
                jdbc.update("INSERT INTO PUBLIC.INVENTORY (SKU, PRODUCT, QUANTITY) VALUES (?, ?, ?)", sku, name, quantity);
                row.put("status", "CREATED").put("sku", sku).put("name", name).put("quantity", quantity);
            } catch (DuplicateKeyException ex) {
                row.put("status", "DUPLICATE_SKU").put("sku", sku).put("message", "That SKU already exists. No product was created.");
            }
        } else {
            row.put("status", "CANCELLED").put("message", "No product was created.");
        }
        var response = new ChatResponse();
        response.setData(objectMapper.createArrayNode().add(row));
        return response;
    }

    static String validate(String field, String raw) {
        String value = raw.strip();
        return switch (field) {
            case "sku" -> {
                if (!value.matches("[A-Za-z0-9_-]{1,12}")) {
                    throw new IllegalArgumentException("Enter an SKU of 1–12 letters, digits, hyphens, or underscores.");
                }
                yield value.toUpperCase(Locale.ROOT);
            }
            case "name" -> {
                if (value.isBlank() || value.length() > 80 || value.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Enter a product name of 1–80 characters on one line.");
                }
                yield value;
            }
            case "quantity" -> {
                String message = "Enter a whole quantity from 0 to 2147483647.";
                if (!value.matches("[0-9]{1,10}")) {
                    throw new IllegalArgumentException(message);
                }
                try {
                    yield Integer.toString(Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(message, ex);
                }
            }
            case "confirmation" -> {
                if ("Create product".equalsIgnoreCase(value)) {
                    yield "Create product";
                }
                if ("Cancel".equalsIgnoreCase(value)) {
                    yield "Cancel";
                }
                throw new IllegalArgumentException("Answer Create product or Cancel.");
            }
            default -> throw new IllegalArgumentException("Unknown product workflow question.");
        };
    }
}

/** Only the two named demo steps use the local executor; all other steps use Symphony defaults. */
@Primary
@Component
class DemoProductStepFactory extends KnowledgeExecuterFactory {
    private final ProductWorkflow product;

    DemoProductStepFactory(ProductWorkflow product) {
        this.product = product;
    }

    @Override
    public IStep getExecuter(Knowledge knowledge) {
        if (knowledge != null && knowledge.getType() == QueryType.PLUGIN
                && (ProductWorkflow.CHECK.equals(knowledge.getName()) || ProductWorkflow.CREATE.equals(knowledge.getName()))) {
            return product;
        }
        return super.getExecuter(knowledge);
    }
}