package org.symphonykernel.demo;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.symphonykernel.Knowledge;
import org.symphonykernel.KnowledgeDescription;
import org.symphonykernel.QueryType;
import org.symphonykernel.core.IknowledgeBase;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

@Component
class DemoKnowledge implements IknowledgeBase {
        private static final String BUNDLED_LOCATION = "classpath:/knowledge";
        private record Definition(String name, String description, QueryType type, JsonNode data,
                        JsonNode params, Boolean active, List<String> aliases, String systemPrompt) { }

        private final Map<String, Definition> entries;
        private final Map<String, String> descriptions;
        private final Map<String, String> activeDescriptions;

        DemoKnowledge(ResourcePatternResolver resources,
                        @Value("${demo.knowledge.location:" + BUNDLED_LOCATION + "}") String location) throws IOException {
                String folder = location.endsWith("/") ? location : location + "/";
                var files = Stream.concat(Arrays.stream(resources.getResources(folder + "**/*.yaml")),
                        Arrays.stream(resources.getResources(folder + "**/*.yml"))).toList();
                if (files.isEmpty()) {
                        throw new IllegalStateException("No knowledge YAML files found in " + location);
                }

                ObjectReader reader = YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readerFor(Definition.class);
                Map<String, Definition> loaded = new LinkedHashMap<>();
                Map<String, String> all = new LinkedHashMap<>();
                Map<String, String> active = new LinkedHashMap<>();
                for (Resource file : files) {
                        
                        Definition definition = readDefinition(file, reader);
                        if (loaded.putIfAbsent(definition.name(), definition) != null) {
                                throw new IllegalStateException("Duplicate knowledge name '" + definition.name() + "' in " + file);
                        }
                        all.put(definition.name(), definition.description());
                        if (!Boolean.FALSE.equals(definition.active())) {
                                var aliases = definition.aliases();
                                active.put(definition.name(), aliases == null || aliases.isEmpty() ? definition.description()
                                                : definition.description() + " Examples: " + String.join("; ", aliases) + ".");
                        }
                }
                if (loaded.isEmpty()) {
                        throw new IllegalStateException("No knowledge YAML files found in " + location);
                }
                entries = Map.copyOf(loaded);
                descriptions = Map.copyOf(all);
                activeDescriptions = Map.copyOf(active);
        }

        private static Definition readDefinition(Resource file, ObjectReader reader) {
                try (var input = file.getInputStream()) {
                        Definition definition = reader.readValue(input);
                        if (definition == null || definition.name() == null || definition.name().isBlank()
                                        || !definition.name().equals(definition.name().strip())
                                        || definition.description() == null || definition.description().isBlank()
                                        || definition.type() == null || definition.type() == QueryType.UNKNOWN
                                        || definition.data() == null || definition.data().isNull()
                                        || (definition.data().isTextual() && definition.data().asText().isBlank())) {
                                throw new IllegalArgumentException("Expected a name, description, supported type and non-empty data");
                        }
                        if (definition.type() == QueryType.SQL && !definition.data().isTextual()) {
                                throw new IllegalArgumentException("SQL data must be a string");
                        }
                        if (definition.aliases() != null && definition.aliases().stream()
                                        .anyMatch(alias -> alias == null || alias.codePoints().noneMatch(Character::isLetterOrDigit))) {
                                throw new IllegalArgumentException("Aliases must contain words or numbers");
                        }
                        return definition;
                } catch (IOException | IllegalArgumentException ex) {
                        throw new IllegalStateException("Invalid knowledge definition in " + file + ": " + ex.getMessage(), ex);
                }
        }

        private static String toSymphonyData(JsonNode value) {
                if (value == null || value.isNull()) {
                        return null;
                }
                return value.isTextual() ? value.asText() : value.toString();
        }

    @Override
    public Knowledge GetByName(String name) {
                Definition entry = name == null ? null : entries.get(name.strip());
                if (entry == null) {
                        return null;
                }
        var knowledge = new Knowledge();
                knowledge.setName(entry.name());
                knowledge.setDesc(entry.description());
        knowledge.setType(entry.type());
                knowledge.setData(toSymphonyData(entry.data()));
                knowledge.setParams(toSymphonyData(entry.params()));
                knowledge.setSystemPrompt(entry.systemPrompt());
        return knowledge;
    }

        @Override public Map<String, String> getActiveKnowledgeDescriptions() { return activeDescriptions; }
    @Override public Map<String, String> getAllKnowledgeDescriptions() { return descriptions; }
        @Override public String getKnowledgeDescriptions(String name) {
                return name == null ? null : descriptions.getOrDefault(name, name);
        }
    @Override public String GetViewDefByName(String name) { return null; }
    @Override public Map<String, String> getAllVewDescriptions() { return Map.of(); }
    @Override public List<KnowledgeDescription> getDocuments(Date from) { return List.of(); }
}