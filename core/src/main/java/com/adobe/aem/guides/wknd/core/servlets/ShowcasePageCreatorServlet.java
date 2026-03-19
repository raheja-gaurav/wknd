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
 * Uses the "showcase" service user (wknd-showcase-service) which has
 * read/write access to /content/showcase for creating pages.
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

        Map<String, Object> authInfo = Collections.singletonMap(
                ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {

            // 1. Parse JSON from Claude
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
