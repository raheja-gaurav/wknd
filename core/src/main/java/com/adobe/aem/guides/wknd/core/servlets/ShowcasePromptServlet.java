package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Servlet 1 of 2 — Prompt Builder
 *
 * Path : POST /bin/showcase/prompt
 *
 * Uses the "showcase" service user (wknd-showcase-service) which has
 * read access to /apps/wknd so it can read _cq_dialog XML.
 */
@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/prompt")
public class ShowcasePromptServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcasePromptServlet.class);

    /** Subservice name — must match the service user mapper config. */
    private static final String SUBSERVICE = "showcase";

    @Reference
    private ResourceResolverFactory resolverFactory;

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

        Map<String, Object> authInfo = Collections.singletonMap(
                ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {

            // 1. Read dialog XML
            String dialogXML = ShowcaseUtils.readDialogXML(resolver, resourceType);
            if (dialogXML == null) {
                response.getWriter().print(
                    ShowcaseUtils.error("No _cq_dialog found for: /apps/" + resourceType));
                return;
            }

            // 2. Read optional config
            JSONObject config = ShowcaseUtils.readShowcaseConfig(resolver, resourceType);

            // 3. Build the prompt
            String prompt = ShowcaseUtils.buildPrompt(dialogXML, config, resourceType);
            log.info("[ShowcasePrompt] Prompt ready ({} chars)", prompt.length());

            // 4. Return prompt
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
