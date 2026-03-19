package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/generate")
public class ShowcaseGeneratorServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseGeneratorServlet.class);

    // ─── OSGi Config — set your AI endpoint here ───────────────────────────
    // In AEM: /system/console/configMgr → ShowcaseGeneratorServlet
    // OR hardcode for hackathon demo (see STEP 3 below)

    // AI Agent endpoint — replace with your Copilot/Groq/OpenAI URL
    private static final String AI_ENDPOINT =
            System.getenv("AI_ENDPOINT") != null
                    ? System.getenv("AI_ENDPOINT")
                    : "https://api.groq.com/openai/v1/chat/completions";

    // AI API Key — replace with your key
    private static final String AI_API_KEY =
            System.getenv("AI_API_KEY") != null
                    ? System.getenv("AI_API_KEY")
                    : "YOUR_API_KEY_HERE";

    // Model name — change based on provider
    private static final String AI_MODEL = "llama-3.3-70b-versatile"; // Groq free

    // Where showcase pages are created in AEM
    private static final String SHOWCASE_ROOT = "/content/showcase";

    // ───────────────────────────────────────────────────────────────────────

    @Override
    protected void doPost(SlingHttpServletRequest request,
                          SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        // ── STEP 1: Get resource type from request ──────────────────────────
        // e.g. "wknd/components/teaser"
        String resourceType = request.getParameter("resourceType");

        if (resourceType == null || resourceType.isEmpty()) {
            out.print(error("resourceType parameter is required"));
            return;
        }

        log.info("[Showcase] Starting generation for: {}", resourceType);

        ResourceResolver resolver = request.getResourceResolver();

        try {

            // ── STEP 2: Read _cq_dialog/.content.xml ───────────────────────
            String dialogXML = readDialogXML(resolver, resourceType);
            if (dialogXML == null) {
                out.print(error("No _cq_dialog found for: /apps/" + resourceType));
                return;
            }
            log.info("[Showcase] Dialog XML read successfully ({} chars)", dialogXML.length());

            // ── STEP 3: Read showcase-config.json (optional) ───────────────
            JSONObject config = readShowcaseConfig(resolver, resourceType);
            log.info("[Showcase] Config loaded: {}", config.has("component") ? config.getString("component") : "no config found, using defaults");

            // ── STEP 4: Build AI prompt ─────────────────────────────────────
            String prompt = buildPrompt(dialogXML, config, resourceType);
            log.info("[Showcase] Prompt built ({} chars)", prompt.length());

            // ── STEP 5: Call AI Agent ───────────────────────────────────────
            log.info("[Showcase] Calling AI agent at: {}", AI_ENDPOINT);
            String aiResponse = callAI(prompt);
            log.info("[Showcase] AI response received ({} chars)", aiResponse.length());

            // ── STEP 6: Parse AI JSON response ──────────────────────────────
            JSONObject variations = parseAIResponse(aiResponse);
            log.info("[Showcase] Parsed {} variations, {} edge cases",
                    variations.getJSONArray("variations").length(),
                    variations.getJSONArray("edgeCases").length());

            // ── STEP 7: Create showcase page in AEM ─────────────────────────
            String componentName = extractComponentName(resourceType);
            String showcasePath = createShowcasePage(
                    resolver, resourceType, componentName, variations);
            log.info("[Showcase] Showcase page created at: {}", showcasePath);

            // ── STEP 8: Return success ──────────────────────────────────────
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("showcasePath", showcasePath);
            result.put("editorUrl", "/editor.html" + showcasePath + ".html");
            result.put("previewUrl", showcasePath + ".html");
            result.put("variationCount", variations.getJSONArray("variations").length());
            result.put("edgeCaseCount", variations.getJSONArray("edgeCases").length());

            out.print(result.toString());

        } catch (Exception e) {
            log.error("[Showcase] Generation failed for {}: {}", resourceType, e.getMessage(), e);
            out.print(error(e.getMessage()));
            response.setStatus(500);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 2: READ DIALOG XML
    // Reads /apps/{resourceType}/_cq_dialog/.content.xml
    // ────────────────────────────────────────────────────────────────────────
    private String readDialogXML(ResourceResolver resolver, String resourceType) {
        try {
            String dialogPath = "/apps/" + resourceType + "/_cq_dialog/.content.xml";
            Resource dialogResource = resolver.getResource(dialogPath);

            if (dialogResource == null) {
                log.warn("[Showcase] Dialog not found at: {}", dialogPath);
                return null;
            }

            // Read as InputStream → String
            InputStream is = dialogResource.adaptTo(InputStream.class);
            if (is == null) {
                // Try reading via jcr:data
                Resource jcrContent = dialogResource.getChild("jcr:content");
                if (jcrContent != null) {
                    is = jcrContent.adaptTo(InputStream.class);
                }
            }

            if (is != null) {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            }

            // Fallback: read via ValueMap (works for nt:unstructured nodes)
            // Serialize the JCR node properties as a simple string
            return serializeNodeAsString(resolver, dialogPath);

        } catch (Exception e) {
            log.error("[Showcase] Failed to read dialog XML: {}", e.getMessage());
            return null;
        }
    }

    // Fallback: serialize JCR node to readable string for AI
    private String serializeNodeAsString(ResourceResolver resolver, String path) {
        StringBuilder sb = new StringBuilder();
        Resource res = resolver.getResource(path);
        if (res == null) return null;
        sb.append("<dialog path=\"").append(path).append("\">\n");
        serializeResource(res, sb, 0);
        sb.append("</dialog>");
        return sb.toString();
    }

    private void serializeResource(Resource resource, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append("<node name=\"").append(resource.getName()).append("\"");
        resource.getValueMap().forEach((k, v) -> {
            if (!k.startsWith("jcr:created") && !k.startsWith("jcr:lastModified")) {
                sb.append(" ").append(k).append("=\"").append(v.toString()).append("\"");
            }
        });
        sb.append(">\n");
        resource.getChildren().forEach(child -> serializeResource(child, sb, depth + 1));
        sb.append(indent).append("</node>\n");
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 3: READ SHOWCASE CONFIG JSON
    // Reads /apps/{resourceType}/showcase-config.json
    // Returns empty JSONObject if file doesn't exist — servlet still works
    // ────────────────────────────────────────────────────────────────────────
    private JSONObject readShowcaseConfig(ResourceResolver resolver,
                                          String resourceType) {
        try {
            String configPath = "/apps/" + resourceType + "/showcase-config.json";
            Resource configResource = resolver.getResource(configPath);

            if (configResource == null) {
                log.info("[Showcase] No showcase-config.json found at {} — using defaults", configPath);
                return new JSONObject();
            }

            InputStream is = configResource.adaptTo(InputStream.class);
            if (is == null) {
                Resource jcrContent = configResource.getChild("jcr:content");
                if (jcrContent != null) {
                    is = jcrContent.adaptTo(InputStream.class);
                }
            }

            if (is != null) {
                String content = IOUtils.toString(is, StandardCharsets.UTF_8);
                return new JSONObject(content);
            }

        } catch (Exception e) {
            log.warn("[Showcase] Could not read showcase-config.json: {}", e.getMessage());
        }

        return new JSONObject();
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 4: BUILD AI PROMPT
    // Combines dialog XML + config into a clear AI prompt
    // ────────────────────────────────────────────────────────────────────────
    private String buildPrompt(String dialogXML,
                               JSONObject config,
                               String resourceType) {

        StringBuilder prompt = new StringBuilder();

        // Core instruction
        prompt.append("You are an AEM (Adobe Experience Manager) expert.\n");
        prompt.append("Generate a component showcase for the following AEM component.\n");
        prompt.append("Return ONLY valid JSON. No markdown. No explanation. Just JSON.\n\n");

        // Component identity
        prompt.append("COMPONENT RESOURCE TYPE: ").append(resourceType).append("\n");
        if (config.has("component")) {
            prompt.append("COMPONENT NAME: ").append(config.getString("component")).append("\n");
        }
        if (config.has("description")) {
            prompt.append("COMPONENT PURPOSE: ").append(config.getString("description")).append("\n");
        }
        prompt.append("\n");

        // Dialog XML
        prompt.append("DIALOG XML (fields authors can configure):\n");
        prompt.append("```xml\n").append(dialogXML).append("\n```\n\n");

        // Asset paths from config
        if (config.has("assetPaths")) {
            prompt.append("USE THESE REAL AEM ASSET PATHS IN YOUR CONTENT:\n");
            JSONObject assets = config.getJSONObject("assetPaths");
            assets.keys().forEachRemaining(key ->
                    prompt.append("  ").append(key).append(": ")
                            .append(assets.getString(key)).append("\n")
            );
            prompt.append("\n");
        }

        // Brand guidelines
        if (config.has("brandGuidelines")) {
            prompt.append("BRAND GUIDELINES:\n");
            JSONObject brand = config.getJSONObject("brandGuidelines");
            brand.keys().forEachRemaining(key ->
                    prompt.append("  ").append(key).append(": ")
                            .append(brand.get(key).toString()).append("\n")
            );
            prompt.append("\n");
        }

        // Variation hints
        if (config.has("variationHints")) {
            prompt.append("VARIATION HINTS (follow these):\n");
            config.getJSONArray("variationHints").forEach(hint ->
                    prompt.append("  - ").append(hint).append("\n")
            );
            prompt.append("\n");
        }

        // Edge case hints
        if (config.has("edgeCaseHints")) {
            prompt.append("EDGE CASE HINTS (follow these):\n");
            config.getJSONArray("edgeCaseHints").forEach(hint ->
                    prompt.append("  - ").append(hint).append("\n")
            );
            prompt.append("\n");
        }

        // Field notes
        if (config.has("fieldNotes")) {
            prompt.append("FIELD NOTES:\n");
            JSONObject notes = config.getJSONObject("fieldNotes");
            notes.keys().forEachRemaining(key ->
                    prompt.append("  ").append(key).append(": ")
                            .append(notes.getString(key)).append("\n")
            );
            prompt.append("\n");
        }

        // Output format instruction
        prompt.append("REQUIRED OUTPUT FORMAT (strict JSON, no deviations):\n");
        prompt.append("{\n");
        prompt.append("  \"componentName\": \"string\",\n");
        prompt.append("  \"description\": \"one sentence what this component does\",\n");
        prompt.append("  \"variations\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"v1\",\n");
        prompt.append("      \"label\": \"Short descriptive name\",\n");
        prompt.append("      \"description\": \"When an author would use this\",\n");
        prompt.append("      \"fields\": { \"fieldName\": \"value\" }\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"edgeCases\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"e1\",\n");
        prompt.append("      \"label\": \"Edge case name\",\n");
        prompt.append("      \"issue\": \"What might break and why\",\n");
        prompt.append("      \"severity\": \"red\",\n");
        prompt.append("      \"fields\": { \"fieldName\": \"value\" }\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n\n");
        prompt.append("Rules:\n");
        prompt.append("- Maximum 5 variations\n");
        prompt.append("- Maximum 3 edge cases\n");
        prompt.append("- Use ONLY field names that exist in the dialog XML\n");
        prompt.append("- Use real asset paths from config if provided\n");
        prompt.append("- Generate realistic enterprise website content\n");
        prompt.append("- No lorem ipsum\n");

        return prompt.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 5: CALL AI AGENT
    // Works with Groq, OpenAI, Azure OpenAI, GitHub Copilot
    // All use OpenAI-compatible API format
    // ────────────────────────────────────────────────────────────────────────
    private String callAI(String prompt) throws Exception {

        // Build OpenAI-compatible request body
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", AI_MODEL);
        requestBody.put("max_tokens", 2000);
        requestBody.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content",
                "You are an AEM expert. Return ONLY valid JSON. No markdown fences. No explanation.");
        messages.put(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.put(userMsg);

        requestBody.put("messages", messages);

        // HTTP call
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AI_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AI_API_KEY)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "AI call failed with status " + response.statusCode()
                            + ": " + response.body());
        }

        // Extract text from OpenAI response format
        JSONObject responseJson = new JSONObject(response.body());
        return responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 6: PARSE AI RESPONSE
    // Strips markdown fences if AI added them, parses JSON
    // ────────────────────────────────────────────────────────────────────────
    private JSONObject parseAIResponse(String rawResponse) {
        String cleaned = rawResponse.trim();

        // Strip markdown code fences if present
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        JSONObject parsed = new JSONObject(cleaned);

        // Ensure required keys exist with defaults
        if (!parsed.has("variations")) parsed.put("variations", new JSONArray());
        if (!parsed.has("edgeCases"))  parsed.put("edgeCases", new JSONArray());
        if (!parsed.has("componentName")) parsed.put("componentName", "Component");
        if (!parsed.has("description")) parsed.put("description", "");

        return parsed;
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 7: CREATE SHOWCASE PAGE IN AEM
    // Creates /content/showcase/{componentName}
    // Writes variations + edge cases as JCR nodes
    // ────────────────────────────────────────────────────────────────────────
    private String createShowcasePage(ResourceResolver resolver,
                                      String resourceType,
                                      String componentName,
                                      JSONObject variations)
            throws Exception {

        Session session = resolver.adaptTo(Session.class);
        if (session == null) throw new RuntimeException("Could not get JCR Session");

        String pageNodeName = componentName.toLowerCase().replaceAll("[^a-z0-9]", "-");
        String pagePath = SHOWCASE_ROOT + "/" + pageNodeName;

        // ── Ensure /content/showcase exists ──────────────────────────────
        ensureNode(session, SHOWCASE_ROOT, "cq:Page");
        Node showcaseRoot = session.getNode(SHOWCASE_ROOT);
        Node jcrContentRoot = getOrCreateNode(showcaseRoot, "jcr:content", "cq:PageContent");
        jcrContentRoot.setProperty("jcr:title", "Component Showcase");
        jcrContentRoot.setProperty("sling:resourceType", "wknd/components/page");

        // ── Create/overwrite showcase page ───────────────────────────────
        if (session.nodeExists(pagePath)) {
            session.getNode(pagePath).remove();
        }

        Node pageNode = showcaseRoot.addNode(pageNodeName, "cq:Page");

        // jcr:content
        Node pageContent = pageNode.addNode("jcr:content", "cq:PageContent");
        pageContent.setProperty("jcr:title",
                variations.getString("componentName") + " — Showcase");
        pageContent.setProperty("sling:resourceType", "wknd/components/page");
        pageContent.setProperty("showcase:resourceType", resourceType);
        pageContent.setProperty("showcase:description",
                variations.getString("description"));
        pageContent.setProperty("showcase:generatedAt",
                java.time.Instant.now().toString());

        // root layout container
        Node root = pageContent.addNode("root", "nt:unstructured");
        root.setProperty("sling:resourceType",
                "wcm/foundation/components/responsivegrid");

        // ── Write VARIATIONS ──────────────────────────────────────────────
        JSONArray varArray = variations.getJSONArray("variations");
        for (int i = 0; i < varArray.length(); i++) {
            JSONObject variation = varArray.getJSONObject(i);

            // Heading node (title component labels each variation)
            Node heading = root.addNode("heading_v" + i, "nt:unstructured");
            heading.setProperty("sling:resourceType",
                    "core/wcm/components/title/v2/title");
            heading.setProperty("jcr:title",
                    variation.getString("label"));
            heading.setProperty("type", "h2");
            heading.setProperty("showcase:variationDescription",
                    variation.getString("description"));

            // Actual component node with all fields
            Node comp = root.addNode("component_v" + i, "nt:unstructured");
            comp.setProperty("sling:resourceType", resourceType);
            comp.setProperty("showcase:variationId", variation.getString("id"));

            JSONObject fields = variation.getJSONObject("fields");
            fields.keys().forEachRemaining(key -> {
                try {
                    comp.setProperty(key, fields.getString(key));
                } catch (Exception e) {
                    log.warn("[Showcase] Could not set field {}: {}", key, e.getMessage());
                }
            });
        }

        // ── Write EDGE CASES ──────────────────────────────────────────────
        if (varArray.length() > 0) {
            // Divider heading
            Node edgeHeading = root.addNode("heading_edge", "nt:unstructured");
            edgeHeading.setProperty("sling:resourceType",
                    "core/wcm/components/title/v2/title");
            edgeHeading.setProperty("jcr:title", "Edge Cases");
            edgeHeading.setProperty("type", "h2");
        }

        JSONArray edgeArray = variations.getJSONArray("edgeCases");
        for (int i = 0; i < edgeArray.length(); i++) {
            JSONObject edgeCase = edgeArray.getJSONObject(i);

            Node edgeHeading = root.addNode("heading_e" + i, "nt:unstructured");
            edgeHeading.setProperty("sling:resourceType",
                    "core/wcm/components/title/v2/title");
            edgeHeading.setProperty("jcr:title",
                    "⚠ " + edgeCase.getString("label"));
            edgeHeading.setProperty("type", "h3");

            Node edgeComp = root.addNode("edge_e" + i, "nt:unstructured");
            edgeComp.setProperty("sling:resourceType", resourceType);
            edgeComp.setProperty("showcase:edgeCaseId", edgeCase.getString("id"));
            edgeComp.setProperty("showcase:issue", edgeCase.getString("issue"));
            edgeComp.setProperty("showcase:severity", edgeCase.getString("severity"));

            JSONObject fields = edgeCase.getJSONObject("fields");
            fields.keys().forEachRemaining(key -> {
                try {
                    edgeComp.setProperty(key, fields.getString(key));
                } catch (Exception e) {
                    log.warn("[Showcase] Could not set edge case field {}: {}", key, e.getMessage());
                }
            });
        }

        // ── Save session ──────────────────────────────────────────────────
        session.save();
        log.info("[Showcase] Page saved at: {}", pagePath);

        return pagePath;
    }

    // ────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────────────────
    private Node getOrCreateNode(Node parent, String name, String type)
            throws Exception {
        if (parent.hasNode(name)) return parent.getNode(name);
        return parent.addNode(name, type);
    }

    private void ensureNode(Session session, String path, String type)
            throws Exception {
        if (!session.nodeExists(path)) {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            String nodeName   = path.substring(path.lastIndexOf('/') + 1);
            ensureNode(session, parentPath, "nt:unstructured");
            session.getNode(parentPath).addNode(nodeName, type);
            session.save();
        }
    }

    private String extractComponentName(String resourceType) {
        // "wknd/components/teaser" → "teaser"
        return resourceType.substring(resourceType.lastIndexOf('/') + 1);
    }

    private String error(String message) {
        JSONObject e = new JSONObject();
        e.put("status", "error");
        e.put("message", message);
        return e.toString();
    }
}