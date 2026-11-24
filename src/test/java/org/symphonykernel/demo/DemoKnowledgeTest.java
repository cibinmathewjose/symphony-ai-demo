package org.symphonykernel.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.symphonykernel.QueryType;

import com.fasterxml.jackson.databind.ObjectMapper;

class DemoKnowledgeTest {
    @TempDir Path folder;

    private static final String LOOKUP = """
            name: custom-lookup
            description: Look up a sample row
            type: SQL
            aliases: [find catalog items]
            data: >-
              SELECT SKU
              FROM INVENTORY
            """;

    @Test
    void readsBothExtensionsFromAnExternalFolder() throws IOException {
        Files.writeString(folder.resolve("lookup.yml"), LOOKUP);
        Path nested = Files.createDirectory(folder.resolve("workflow"));
        Files.writeString(nested.resolve("approval.yaml"), String.join("\n",
                "name: custom-approval", "description: Ask for input", "type: HUMANLOOP", "active: false",
                "aliases: [ask for approval]", "params: {sku: string}", "data:",
                "  question: Approve?", "  options: ['Yes', 'No']"));

        DemoKnowledge knowledge = loadFolder();
        assertThat(knowledge.GetByName("inventory")).isNull();
        assertThat(knowledge.GetByName("custom-lookup").getData()).isEqualTo("SELECT SKU FROM INVENTORY");
        assertThat(knowledge.getActiveKnowledgeDescriptions()).containsOnlyKeys("custom-lookup");
        assertThat(knowledge.getAllKnowledgeDescriptions()).containsOnlyKeys("custom-lookup", "custom-approval");
        assertThat(knowledge.getActiveKnowledgeDescriptions()).containsEntry("custom-lookup",
          "Look up a sample row Examples: find catalog items.");

        var approval = knowledge.GetByName("custom-approval");
        var mapper = new ObjectMapper();
        assertThat(approval.getType()).isEqualTo(QueryType.HUMANLOOP);
        assertThat(approval.getDesc()).isEqualTo("Ask for input");
        assertThat(mapper.readTree(approval.getData()).path("options").path(0).asText()).isEqualTo("Yes");
        assertThat(mapper.readTree(approval.getParams()).path("sku").asText()).isEqualTo("string");
    }

    @Test
    void rejectsDuplicateNames() throws IOException {
        Files.writeString(folder.resolve("lookup.yml"), LOOKUP);
        Files.writeString(folder.resolve("duplicate.yaml"), LOOKUP);
        assertThatThrownBy(this::loadFolder).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate knowledge name 'custom-lookup'");
    }

   

    @Test
    void reportsTheFileWithAnInvalidDefinition() throws IOException {
        Files.writeString(folder.resolve("invalid.yaml"), LOOKUP.replace("type: SQL", "type: NOT_A_TYPE"));
        assertThatThrownBy(this::loadFolder).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid knowledge definition").hasMessageContaining("invalid.yaml");
    }

    private DemoKnowledge loadFolder() throws IOException {
        return new DemoKnowledge(new PathMatchingResourcePatternResolver(), folder.toUri().toString());
    }
}