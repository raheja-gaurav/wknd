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
 * Servlet 2 of 2 — Page Creator
 *
 * Path : POST /bin/showcase/createPage
 *
 * Accepts the full AEM page-importable JSON (the array format Claude returns)
 * and writes it directly into JCR under /content/showcase.
 */
@Component(service = Servlet.class)
@SlingServletPaths("/bin/showcase/createPage")
public class ShowcasePageCreatorServlet extends SlingAllMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(ShowcasePageCreatorServlet.class);

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
        String pageJson     = request.getParameter("pageJson");

        if (resourceType == null || resourceType.isEmpty()) {
            response.getWriter().print(ShowcaseUtils.error("resourceType parameter is required"));
            return;
        }
        if (pageJson == null || pageJson.isEmpty()) {
            response.getWriter().print(ShowcaseUtils.error("pageJson parameter is required"));
            return;
        }

        // Strip markdown fences if Claude wrapped the JSON
        pageJson = pageJson.trim();
        if (pageJson.startsWith("```json")) pageJson = pageJson.substring(7);
        else if (pageJson.startsWith("```")) pageJson = pageJson.substring(3);
        if (pageJson.endsWith("```")) pageJson = pageJson.substring(0, pageJson.length() - 3);
        pageJson = pageJson.trim();

        log.info("[ShowcasePageCreator] Creating page for: {} ({} chars of JSON)", resourceType, pageJson.length());

        Map<String, Object> authInfo = Collections.singletonMap(
                ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {

            // Import the page JSON directly into JCR
            String showcasePath = ShowcaseUtils.createShowcasePage(resolver, resourceType, pageJson);
            log.info("[ShowcasePageCreator] Page created at: {}", showcasePath);

            // Return page details
            JSONObject result = new JSONObject();
            result.put("status",       "success");
            result.put("showcasePath", showcasePath);
            result.put("editorUrl",    "/editor.html" + showcasePath + ".html");
            result.put("previewUrl",   showcasePath + ".html");
            response.getWriter().print(result.toString());

        } catch (Exception e) {
            log.error("[ShowcasePageCreator] Failed for {}: {}", resourceType, e.getMessage(), e);
            response.setStatus(500);
            response.getWriter().print(ShowcaseUtils.error(e.getMessage()));
        }
    }
}
