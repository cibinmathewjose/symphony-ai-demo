# Symphony AI Demo

A local, unauthenticated chat host for `org.symphonykernel:symphony-ai`. It uses embedded H2, in-memory sessions, and Symphony's standard `SpringAIHelper`. No external database or Redis instance is needed. Chat requires a configured Azure OpenAI deployment.

## Run locally

1. Install `org.symphonykernel:symphony-ai:1.1.0-SNAPSHOT` into your local Maven repository from the sibling library project (`mvn install`).
2. Set `AZURE_OPENAI_API_KEY` in [.env](.env). If the file is missing, copy [.env.example](.env.example) to create it. Use `NAME=value` without quotes or `export`. The demo defaults to the endpoint and deployments (`gpt-4.1` for Symphony and `model-router` for Spring AI). Optionally set `AZURE_OPENAI_ENDPOINT` and `AZURE_OPENAI_DEPLOYMENT` to override them. Environment variables take precedence, and the local file is ignored by Git.
3. With Java 17+ and Maven, run `mvn spring-boot:run` from the demo project directory, or set the Java debugger's working directory to the demo project directory. Spring Boot loads the local file relative to that directory at startup.
4. Open `http://127.0.0.1:8085` and try **Show inventory** or **Start a stock review**. The review looks up inventory, pauses for a human decision, and resumes on the next message. `mvn test` uses a test-only chat model and does not require credentials.

The application intentionally listens on loopback only. Do not expose it publicly: there is no login and sessions are reset on restart.

## YAML knowledge definitions

The demo loads one definition per `.yaml` or `.yml` file from [src/main/resources/knowledge](src/main/resources/knowledge) and its subfolders at startup. Try **Show inventory**, **Start a stock review**, or **What is the return policy?** for a [YAML-authored answer](src/main/resources/knowledge/policies/returns.yml). H2 still holds the inventory data; YAML defines what Symphony can run.

Each file needs a unique `name`, a `description`, a Symphony `type`, and `data`. Use text for SQL and YAML mappings or lists for structured data; the loader converts those to JSON for Symphony. Optional `params` are converted the same way. Set `active: false` for a workflow-only definition that should be available by name but omitted from the active chat catalog.

Symphony asks the model to select from the active knowledge names and descriptions. Optional `aliases` are passed as example phrases in that catalog. For a text answer grounded in YAML, use `type: TOOL` with `data.SystemPrompt` containing the facts and response instructions.

A message runs one matched definition. To use several files in one answer, define a `SYMPHONY` workflow that references them by name, as in [review.yaml](src/main/resources/knowledge/review.yaml), which uses the inventory query and the approval step. The router does not automatically run or merge multiple unrelated matches.

To use an external folder, copy the sample files there and set `demo.knowledge.location` to a Spring resource folder such as `file:./demo-knowledge/` or `file:/F:/demo-knowledge/`. This replaces the bundled folder rather than merging with it. Use only trusted definitions: they control what the application executes. Restart after edits; no Java routing changes are needed to add new topics.

### H2 natural-language SQL

[H2_DB](src/main/resources/knowledge/h2-db.yaml) is a `DATABASE` definition, separate from the fixed **Show inventory** lookup. It uses Symphony's database step to inspect `PUBLIC.INVENTORY`, generate a SELECT, and stream the results. The named JDBC connection uses a local reader granted SELECT on that table only. To expose more objects, configure `data.tables` or `data.views` and grant the reader SELECT on those objects deliberately.

Try **Which products have fewer than 15 units in stock?**, **How many products are in stock?**, **What is the total inventory quantity?**, or **How many units are in stock for SKU P42?** Symphony passes the YAML `systemPrompt`, question, and JDBC metadata for the selected table to Spring AI. No Java question patterns or SQL templates are needed for this definition.

## API

### Add a product

Click **Add product** to collect the SKU, product name, and starting quantity. The [Symphony workflow](src/main/resources/knowledge/products/add-product.yaml) asks only for missing fields and pauses for explicit confirmation. **Create product** inserts a row into local H2; **Cancel** leaves inventory unchanged. Existing SKUs are never overwritten. The product workflow does not call the model.

For API clients, `POST /api/products/add` uses the same SSE envelope as chat. Start with `Content-Type: application/json`, `Accept: text/event-stream`, and a body containing `sessionId` (a UUID) plus any known `sku`, `name`, or integer `quantity`. For each `AWAITING_INPUT` event, POST the same `sessionId` with `answer` (a string) to the same endpoint. Invalid answers leave the question pending. On confirmation, the final content reports `CREATED`, `DUPLICATE_SKU`, or `CANCELLED`. Products are lost when the demo restarts because H2 is in memory.

### Chat

`POST /directchat/stream` is the streaming endpoint used by the browser. Send `Content-Type: application/json`, `Accept: text/event-stream`, and a body such as `{"sessionId":"c2b8d275-8349-4a4c-b75f-f07982071a00","message":"Start a stock review"}`. The Copilot field names `session` and `query` are also accepted. Each SSE `data:` frame carries a Symphony JSON event with an `eventType` such as `status`, `content`, `complete`, or `error`.

To demo it, open the browser's Network panel, click **Start a stock review**, and inspect the `/directchat/stream` request. The first stream reports progress and closes when the workflow asks for input. Click **Approve review** to send a second request with the same session ID and stream the model's confirmation. **Show inventory** demonstrates streaming a SQL result instead.

`POST /api/chat` remains available for clients that expect newline-delimited JSON (`application/x-ndjson`). It uses the same request body and chat workflow.