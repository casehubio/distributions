package io.casehub.mcp.it;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Quick-start examples and integration tests for the CaseHub MCP server.
 *
 * Prerequisites:
 *   mvn -f mcp-server/pom.xml clean package -DskipTests -Dhttp
 *
 * Run (direct tools only — no LLM needed):
 *   mvn -f examples/pom.xml verify
 *
 * Run with LLM (Ollama default — requires Ollama running with llama3.1:8b):
 *   mvn -f examples/pom.xml verify -Dllm.provider=ollama
 *
 * Run with Anthropic:
 *   mvn -f examples/pom.xml verify -Dllm.provider=anthropic -Dllm.model=claude-sonnet-4-20250514
 */
class McpServerIT {

    static final int PORT = 18765;
    static McpClient mcpClient;
    static Process serverProcess;

    static Path serverDir() {
        String override = System.getProperty("mcp.server.dir");
        if (override != null) return Path.of(override);
        return Path.of(System.getProperty("user.dir"))
                   .resolve("../mcp-server/target/quarkus-app");
    }

    @BeforeAll
    static void startMcpServer() throws Exception {
        Path dir = serverDir().toAbsolutePath();
        Path jar = dir.resolve("quarkus-run.jar");
        assertThat(jar).as("MCP server JAR — build with: mvn -f mcp-server/pom.xml clean package -DskipTests -Dhttp")
                .exists();

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();

        serverProcess = new ProcessBuilder(javaBin,
                "-Dquarkus.http.port=" + PORT,
                "-Dquarkus.http.host=127.0.0.1",
                "-jar", jar.toString())
                .directory(dir.toFile())
                .start();

        drainStderr();
        waitForServerReady();

        var transport = new HttpMcpTransport.Builder()
                .sseUrl("http://127.0.0.1:" + PORT + "/mcp/sse")
                .timeout(Duration.ofSeconds(30))
                .build();

        mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }

    static void drainStderr() {
        Thread t = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println("[mcp-server] " + line);
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    static void waitForServerReady() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + PORT + "/mcp/sse")
                        .toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
                return;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        throw new IllegalStateException("MCP server did not start within 30 seconds");
    }

    @AfterAll
    static void stopMcpServer() throws Exception {
        if (mcpClient != null) mcpClient.close();
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
            serverProcess.waitFor();
        }
    }

    // --- Model configuration ---

    static ChatModel buildModel() {
        String provider = System.getProperty("llm.provider", "ollama");
        return switch (provider) {
            case "ollama" -> {
                String model = System.getProperty("llm.model", "llama3.1:8b");
                String baseUrl = System.getProperty("llm.base-url", "http://localhost:11434");
                yield OllamaChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(model)
                        .timeout(Duration.ofSeconds(120))
                        .build();
            }
            case "anthropic" -> {
                String apiKey = System.getenv("ANTHROPIC_API_KEY");
                assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY not set");
                String model = System.getProperty("llm.model", "claude-sonnet-4-20250514");
                yield AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(model)
                        .build();
            }
            default -> throw new IllegalArgumentException("Unknown llm.provider: " + provider);
        };
    }

    static void assumeModelAvailable() {
        String provider = System.getProperty("llm.provider", "ollama");
        if ("ollama".equals(provider)) {
            String baseUrl = System.getProperty("llm.base-url", "http://localhost:11434");
            try {
                var conn = (HttpURLConnection) URI.create(baseUrl).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                assumeTrue(code == 200, "Ollama not reachable at " + baseUrl);
            } catch (Exception e) {
                assumeTrue(false, "Ollama not reachable at " + baseUrl + ": " + e.getMessage());
            }
        } else if ("anthropic".equals(provider)) {
            assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");
        }
    }

    interface Assistant {
        String chat(String message);
    }

    static Assistant buildAssistant(ChatModel model) {
        ToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();
    }

    // =========================================================================
    // Layer 1: Direct tool calls — no LLM needed
    // Verifies the MCP server starts, tools are registered, and ref data is there.
    // =========================================================================

    @Nested
    class Layer1_DirectTools {

        @Test
        void listVoices() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_voice_listVoices").arguments("{}").build());
            assertThat(result).contains("af_heart").contains("kokoro");
        }

        @Test
        void speakAndTranscribe() {
            String speakResult = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_voice_speak")
                    .arguments("{\"text\": \"Hello world\"}").build());
            assertThat(speakResult).contains("audioBase64");

            String audioBase64 = speakResult.replaceAll(
                    ".*\"audioBase64\"\\s*:\\s*\"([^\"]+)\".*", "$1");

            String transcribeResult = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_voice_transcribe")
                    .arguments("{\"audioBase64\": \"" + audioBase64 + "\"}").build());
            assertThat(transcribeResult).containsIgnoringCase("hello");
        }

        @Test
        void listCalendars() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_calendar_listCalendars")
                    .arguments("{\"platform\": \"ref\"}").build());
            assertThat(result).contains("cal-jonny").contains("cal-sarah");
        }

        @Test
        void connectorStatus() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_operations_connectorStatus")
                    .arguments("{}").build());
            assertThat(result).contains("email").contains("slack");
        }

        @Test
        void bankBalance() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_bank_balance")
                    .arguments("{\"platform\": \"ref\", \"accountId\": \"acc-100\"}").build());
            assertThat(result).contains("3245");
        }

        @Test
        void emailMailboxes() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_email_listMailboxes")
                    .arguments("{\"platform\": \"ref\"}").build());
            assertThat(result).contains("inbox");
        }

        @Test
        void healthProvider() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_health_findProvider")
                    .arguments("{\"platform\": \"ref\", \"query\": \"Riverside\"}").build());
            assertThat(result).contains("Riverside");
        }

        @Test
        void mobileNumbers() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("connectors_mobile_listNumbers")
                    .arguments("{\"platform\": \"ref\"}").build());
            assertThat(result).contains("441234567");
        }
    }

    // =========================================================================
    // Layer 2: Corpus management — direct tool calls
    // Verifies neocortex corpus tools are registered and callable.
    // With NoOp defaults, corpora are empty; validates tool wiring, not data.
    // =========================================================================

    @Nested
    class Layer2_Corpus {

        @Test
        void listCorpora() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("corpus_management_listCorpora")
                    .arguments("{\"lifecycle\": \"ACTIVE\"}").build());
            assertThat(result).isNotNull();
        }

        @Test
        void addAndGetEntry() {
            mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("corpus_management_addEntry")
                    .arguments("{\"corpusName\": \"quickstart\", \"entryId\": \"hello\", " +
                               "\"body\": \"Hello from the quick-start guide.\", " +
                               "\"metadata\": \"{}\"}").build());

            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("corpus_management_getEntry")
                    .arguments("{\"corpusName\": \"quickstart\", \"entryId\": \"hello\"}").build());
            assertThat(result).isNotNull();
        }

        @Test
        void searchCorpus() {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .name("corpus_management_searchCorpus")
                    .arguments("{\"corpusName\": \"quickstart\", \"query\": \"hello\", \"maxResults\": 5}").build());
            assertThat(result).isNotNull();
        }
    }

    // =========================================================================
    // Layer 3: Single-domain LLM — model picks the right tool
    // Requires Ollama (default) or Anthropic. Skipped when no LLM available.
    // =========================================================================

    @Nested
    class Layer3_SingleDomainLLM {

        @Test
        void llmListsCalendars() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "What calendars are available on the 'ref' platform? "
                    + "Use the calendar tools. Return just the calendar IDs.");
            assertThat(answer.toLowerCase()).contains("cal-jonny").contains("cal-sarah");
        }

        @Test
        void llmSearchesContacts() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Search contacts for 'Bob Jenkins' with platformId 'ref'. What is his email?");
            assertThat(answer.toLowerCase()).contains("bob");
        }

        @Test
        void llmSearchesProducts() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Search for products with platform 'ref' and query 'headphones'. What was found?");
            assertThat(answer).satisfiesAnyOf(
                    a -> assertThat(a).containsIgnoringCase("Sony"),
                    a -> assertThat(a).containsIgnoringCase("WH-1000XM5"),
                    a -> assertThat(a).containsIgnoringCase("headphone"));
        }

        @Test
        void llmSearchesTransport() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Search transport with platform 'ref', origin 'London', "
                    + "destination 'Brighton', date '2026-12-01'. What options are available?");
            assertThat(answer.toLowerCase()).satisfiesAnyOf(
                    a -> assertThat(a).containsIgnoringCase("brighton"),
                    a -> assertThat(a).containsIgnoringCase("train"));
        }

        @Test
        void llmChecksBankBalance() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Get the balance for bank account 'acc-100' on platform 'ref'. "
                    + "What is the available balance?");
            assertThat(answer).contains("3245");
        }

        @Test
        void llmReadsEmail() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "List email mailboxes on platform 'ref'. What mailboxes are available?");
            assertThat(answer.toLowerCase()).containsIgnoringCase("inbox");
        }

        @Test
        void llmListsDocuments() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "List files on the 'ref' document platform. What files are available?");
            assertThat(answer.toLowerCase()).satisfiesAnyOf(
                    a -> assertThat(a).containsIgnoringCase("passport"),
                    a -> assertThat(a).containsIgnoringCase("insurance"),
                    a -> assertThat(a).containsIgnoringCase("doc"));
        }
    }

    // =========================================================================
    // Layer 4: Multi-step LLM — model chains tool calls across domains
    // These mirror playbook compound operations, re-implemented as LLM + MCP.
    // =========================================================================

    @Nested
    class Layer4_MultiStepLLM {

        @Test
        void searchAndAddToCart() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Do these steps on the 'ref' commerce platform: "
                    + "1. Search for 'headphones' products. "
                    + "2. Add the first product to my cart (quantity 1). "
                    + "3. View my cart. "
                    + "Tell me what product is in the cart and its price.");
            assertThat(answer).satisfiesAnyOf(
                    a -> assertThat(a).containsIgnoringCase("Sony"),
                    a -> assertThat(a).containsIgnoringCase("WH-1000XM5"),
                    a -> assertThat(a).containsIgnoringCase("cart"));
        }

        @Test
        void findRestaurantAndGetDirections() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Do these steps on the 'ref' location platform: "
                    + "1. Search for restaurants by category 'restaurant' near Balham "
                    + "(latitude 51.443, longitude -0.1525, radius 2000 metres). "
                    + "2. Get the full details of the first restaurant. "
                    + "3. Get walking directions from Balham station "
                    + "(origin lat 51.4432, lng -0.1522) to that restaurant. "
                    + "Tell me the restaurant name and walking duration.");
            assertThat(answer).satisfiesAnyOf(
                    a -> assertThat(a).containsIgnoringCase("Pizza Express"),
                    a -> assertThat(a).containsIgnoringCase("Crown"),
                    a -> assertThat(a).containsIgnoringCase("Balham"));
        }

        @Test
        void lookUpContactAndCheckCalendar() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Search contacts for 'Patel' with platformId 'ref', "
                    + "then list calendars with platform 'ref'. "
                    + "What is Patel's email and what are the calendar IDs?");
            assertThat(answer.toLowerCase()).satisfiesAnyOf(
                    a -> assertThat(a).contains("patel"),
                    a -> assertThat(a).contains("cal-"));
        }

        @Test
        void voiceRoundTrip() {
            assumeModelAvailable();
            Assistant assistant = buildAssistant(buildModel());
            String answer = assistant.chat(
                    "Synthesise the text 'Hello' to speech, then transcribe that audio back. "
                    + "What did the transcription return?");
            assertThat(answer.toLowerCase()).containsIgnoringCase("hello");
        }
    }
}
