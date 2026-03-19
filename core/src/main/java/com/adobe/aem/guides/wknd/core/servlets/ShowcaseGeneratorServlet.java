package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Legacy / fully-automated servlet — does everything in one shot (no human in the loop).
 *
 * Path : POST /bin/showcase/generate
 *
 * Flow:
 *   dialog XML → buildPrompt → AI call → parseJSON → createPage
 *
 * Use this if you have a working AI API key and want to skip the manual copy-paste step.
 * For the human-in-the-loop flow used by the dashboard, see:
 *   ShowcasePromptServlet     (/bin/showcase/prompt)
 *   ShowcasePageCreatorServlet (/bin/showcase/createPage)
 */
@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/generate")
public class ShowcaseGeneratorServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseGeneratorServlet.class);

    // Configure via environment variables or OSGi config
    private static final String AI_ENDPOINT = System.getenv("AI_ENDPOINT") != null
            ? System.getenv("AI_ENDPOINT")
            : "https://api.groq.com/openai/v1/chat/completions";

    private static final String AI_API_KEY = System.getenv("AI_API_KEY") != null
            ? System.getenv("AI_API_KEY")
            : "YOUR_API_KEY_HERE";

    private static final String AI_MODEL = "llama-3.3-70b-versatile";

    @Override
    protected void doPost(SlingHttpServletRequest request,
                          SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String resourceType = request.getParameter("resourceType");
        if (resourceType == null || resourceType.isEmpty()) {
            response.getWriter().print(ShowcaseUtils.error("resourceType parameter is required"));
            return;
        }

        log.info("[ShowcaseGenerator] Auto-generating showcase for: {}", resourceType);
        ResourceResolver resolver = request.getResourceResolver();

        try {
            String dialogXML = ShowcaseUtils.readDialogXML(resolver, resourceType);
            if (dialogXML == null) {
                response.getWriter().print(
                    ShowcaseUtils.error("No _cq_dialog found for: /apps/" + resourceType));
                return;
            }

            JSONObject config   = ShowcaseUtils.readShowcaseConfig(resolver, resourceType);
            String prompt       = ShowcaseUtils.buildPrompt(dialogXML, config, resourceType);
            String aiResponse   = callAI(prompt);
            JSONObject variations = ShowcaseUtils.parseVariationsJson(aiResponse);

            String showcasePath = ShowcaseUtils.createShowcasePage(resolver, resourceType, variations);
            log.info("[ShowcaseGenerator] Done — page at: {}", showcasePath);

            JSONObject result = new JSONObject();
            result.put("status",         "success");
            result.put("showcasePath",   showcasePath);
            result.put("editorUrl",      "/editor.html" + showcasePath + ".html");
            result.put("previewUrl",     showcasePath + ".html");
            result.put("variationCount", variations.getJSONArray("variations").length());
            result.put("edgeCaseCount",  variations.getJSONArray("edgeCases").length());
            response.getWriter().print(result.toString());

        } catch (Exception e) {
            log.error("[ShowcaseGenerator] Failed for {}: {}", resourceType, e.getMessage(), e);
            response.setStatus(500);
            response.getWriter().print(ShowcaseUtils.error(e.getMessage()));
        }
    }

    private String callAI(String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model",       AI_MODEL);
        body.put("max_tokens",  2000);
        body.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
            .put("role", "system")
            .put("content", "You are an AEM expert. Return ONLY valid JSON. No markdown fences. No explanation."));
        messages.put(new JSONObject()
            .put("role", "user")
            .put("content", prompt));
        body.put("messages", messages);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(AI_ENDPOINT))
            .header("Content-Type",  "application/json")
            .header("Authorization", "Bearer " + AI_API_KEY)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> httpResponse = client.send(httpRequest,
            HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 200) {
            throw new RuntimeException("AI call failed: " + httpResponse.statusCode()
                + " — " + httpResponse.body());
        }

        return new JSONObject(httpResponse.body())
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim();
    }
}
