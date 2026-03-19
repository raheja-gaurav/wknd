package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Servlet 1 of 2 — Prompt Builder
 *
 * Path : POST /bin/showcase/prompt
 *
 * What it does:
 *   1. Reads the component's _cq_dialog/.content.xml from JCR
 *   2. Reads optional showcase-config.json for brand/asset hints
 *   3. Builds a detailed AI prompt combining both
 *   4. Returns the prompt as JSON so the dashboard can display it
 *
 * Request params:
 *   resourceType  (required)  e.g. "wknd/components/teaser"
 *
 * Response:
 *   { "status": "success", "prompt": "..." }
 *   { "status": "error",   "message": "..." }
 *
 * The user then copies this prompt, pastes it into Claude, and gets JSON back.
 * That JSON is submitted to ShowcasePageCreatorServlet (/bin/showcase/createPage).
 */
@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/prompt")
public class ShowcasePromptServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcasePromptServlet.class);

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

        log.info("[ShowcasePrompt] Building prompt for: {}", resourceType);
        ResourceResolver resolver = request.getResourceResolver();

        try {
            // 1. Read dialog XML — returns null if no dialog found
            String dialogXML = ShowcaseUtils.readDialogXML(resolver, resourceType);
            if (dialogXML == null) {
                response.getWriter().print(
                    ShowcaseUtils.error("No _cq_dialog found for: /apps/" + resourceType));
                return;
            }

            // 2. Read optional config (brand guidelines, asset paths, hints)
            JSONObject config = ShowcaseUtils.readShowcaseConfig(resolver, resourceType);

            // 3. Build the prompt
            String prompt = ShowcaseUtils.buildPrompt(dialogXML, config, resourceType);
            log.info("[ShowcasePrompt] Prompt ready ({} chars)", prompt.length());

            // 4. Return prompt to dashboard
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("prompt", prompt);
            response.getWriter().print(result.toString());

        } catch (Exception e) {
            log.error("[ShowcasePrompt] Failed for {}: {}", resourceType, e.getMessage(), e);
            response.setStatus(500);
            response.getWriter().print(ShowcaseUtils.error(e.getMessage()));
        }
    }
}
