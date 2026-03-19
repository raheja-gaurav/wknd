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
 * Servlet 2 of 2 — Page Creator
 *
 * Path : POST /bin/showcase/createPage
 *
 * What it does:
 *   1. Receives the JSON that the user got from Claude (pasted in the dashboard popup)
 *   2. Parses it (strips markdown fences if present, fills defaults)
 *   3. Writes the AEM showcase page to JCR under /content/showcase/{componentName}
 *   4. Returns the page path + editor URL
 *
 * Request params:
 *   resourceType    (required)  e.g. "wknd/components/teaser"
 *   variationsJson  (required)  the raw JSON string pasted by the user
 *
 * Response:
 *   {
 *     "status":         "success",
 *     "showcasePath":   "/content/showcase/teaser",
 *     "editorUrl":      "/editor.html/content/showcase/teaser.html",
 *     "previewUrl":     "/content/showcase/teaser.html",
 *     "variationCount": 5,
 *     "edgeCaseCount":  3
 *   }
 *   { "status": "error", "message": "..." }
 */
@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/createPage")
public class ShowcasePageCreatorServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcasePageCreatorServlet.class);

    @Override
    protected void doPost(SlingHttpServletRequest request,
                          SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String resourceType   = request.getParameter("resourceType");
        String variationsJson = request.getParameter("variationsJson");

        if (resourceType == null || resourceType.isEmpty()) {
            response.getWriter().print(ShowcaseUtils.error("resourceType parameter is required"));
            return;
        }
        if (variationsJson == null || variationsJson.isEmpty()) {
            response.getWriter().print(ShowcaseUtils.error("variationsJson parameter is required"));
            return;
        }

        log.info("[ShowcasePageCreator] Creating page for: {}", resourceType);
        ResourceResolver resolver = request.getResourceResolver();

        try {
            // 1. Parse the JSON that the user copied from Claude
            JSONObject variations = ShowcaseUtils.parseVariationsJson(variationsJson);
            log.info("[ShowcasePageCreator] Parsed {} variations, {} edge cases",
                    variations.getJSONArray("variations").length(),
                    variations.getJSONArray("edgeCases").length());

            // 2. Write the showcase page to JCR
            String showcasePath = ShowcaseUtils.createShowcasePage(resolver, resourceType, variations);
            log.info("[ShowcasePageCreator] Page created at: {}", showcasePath);

            // 3. Return page details
            JSONObject result = new JSONObject();
            result.put("status",         "success");
            result.put("showcasePath",   showcasePath);
            result.put("editorUrl",      "/editor.html" + showcasePath + ".html");
            result.put("previewUrl",     showcasePath + ".html");
            result.put("variationCount", variations.getJSONArray("variations").length());
            result.put("edgeCaseCount",  variations.getJSONArray("edgeCases").length());
            response.getWriter().print(result.toString());

        } catch (Exception e) {
            log.error("[ShowcasePageCreator] Failed for {}: {}", resourceType, e.getMessage(), e);
            response.setStatus(500);
            response.getWriter().print(ShowcaseUtils.error(e.getMessage()));
        }
    }
}
