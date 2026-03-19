package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared utilities for the Showcase servlet pair.
 *
 * Used by:
 *   ShowcasePromptServlet    (/bin/showcase/prompt)    — reads dialog, returns prompt
 *   ShowcasePageCreatorServlet (/bin/showcase/createPage) — parses JSON, writes JCR page
 *
 * To add a new field to the prompt, edit buildPrompt().
 * To change how pages are written to JCR, edit createShowcasePage().
 */
public final class ShowcaseUtils {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseUtils.class);

    /** Root path where showcase pages are created. */
    public static final String SHOWCASE_ROOT = "/content/showcase";

    private ShowcaseUtils() {}

    // ─────────────────────────────────────────────────────────────────────────
    // READ DIALOG XML
    // Reads /apps/{resourceType}/_cq_dialog/.content.xml from JCR.
    // Returns null if no dialog exists for this component.
    // ─────────────────────────────────────────────────────────────────────────
    public static String readDialogXML(ResourceResolver resolver, String resourceType) {
        try {
            String dialogPath = "/apps/" + resourceType + "/_cq_dialog/.content.xml";
            Resource dialogResource = resolver.getResource(dialogPath);

            if (dialogResource == null) {
                log.warn("[ShowcaseUtils] Dialog not found at: {}", dialogPath);
                return null;
            }

            // Primary: read directly as InputStream (file node)
            InputStream is = dialogResource.adaptTo(InputStream.class);

            // Fallback 1: jcr:content child (binary node)
            if (is == null) {
                Resource jcrContent = dialogResource.getChild("jcr:content");
                if (jcrContent != null) {
                    is = jcrContent.adaptTo(InputStream.class);
                }
            }

            if (is != null) {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            }

            // Fallback 2: serialize JCR node properties as XML-like string
            // (used when dialog is stored as nt:unstructured in JCR, not as a file)
            return serializeNodeAsString(resolver, dialogPath);

        } catch (Exception e) {
            log.error("[ShowcaseUtils] Failed to read dialog XML: {}", e.getMessage());
            return null;
        }
    }

    private static String serializeNodeAsString(ResourceResolver resolver, String path) {
        Resource res = resolver.getResource(path);
        if (res == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("<dialog path=\"").append(path).append("\">\n");
        serializeResource(res, sb, 0);
        sb.append("</dialog>");
        return sb.toString();
    }

    private static void serializeResource(Resource resource, StringBuilder sb, int depth) {
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

    // ─────────────────────────────────────────────────────────────────────────
    // READ SHOWCASE CONFIG JSON
    // Optional file at /apps/{resourceType}/showcase-config.json.
    // Provides brand guidelines, asset paths, variation hints, etc.
    // Returns empty JSONObject when not found — servlet still works fine.
    // ─────────────────────────────────────────────────────────────────────────
    public static JSONObject readShowcaseConfig(ResourceResolver resolver, String resourceType) {
        try {
            String configPath = "/apps/" + resourceType + "/showcase-config.json";
            Resource configResource = resolver.getResource(configPath);

            if (configResource == null) {
                log.info("[ShowcaseUtils] No showcase-config.json at {} — using defaults", configPath);
                return new JSONObject();
            }

            InputStream is = configResource.adaptTo(InputStream.class);
            if (is == null) {
                Resource jcrContent = configResource.getChild("jcr:content");
                if (jcrContent != null) is = jcrContent.adaptTo(InputStream.class);
            }

            if (is != null) {
                return new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            log.warn("[ShowcaseUtils] Could not read showcase-config.json: {}", e.getMessage());
        }
        return new JSONObject();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUILD AI PROMPT
    // Combines dialog XML + optional config into the prompt shown to the user.
    // ─────────────────────────────────────────────────────────────────────────
    public static String buildPrompt(String dialogXML, JSONObject config, String resourceType) {
        StringBuilder p = new StringBuilder();

        p.append("You are an AEM (Adobe Experience Manager) expert.\n");
        p.append("Generate a component showcase for the following AEM component.\n");
        p.append("Return ONLY valid JSON. No markdown. No explanation. Just JSON.\n\n");

        p.append("COMPONENT RESOURCE TYPE: ").append(resourceType).append("\n");
        if (config.has("component"))   p.append("COMPONENT NAME: ").append(config.getString("component")).append("\n");
        if (config.has("description")) p.append("COMPONENT PURPOSE: ").append(config.getString("description")).append("\n");
        p.append("\n");

        p.append("DIALOG XML (fields authors can configure):\n");
        p.append("```xml\n").append(dialogXML).append("\n```\n\n");

        if (config.has("assetPaths")) {
            p.append("USE THESE REAL AEM ASSET PATHS IN YOUR CONTENT:\n");
            JSONObject assets = config.getJSONObject("assetPaths");
            assets.keys().forEachRemaining(k -> p.append("  ").append(k).append(": ").append(assets.getString(k)).append("\n"));
            p.append("\n");
        }

        if (config.has("brandGuidelines")) {
            p.append("BRAND GUIDELINES:\n");
            JSONObject brand = config.getJSONObject("brandGuidelines");
            brand.keys().forEachRemaining(k -> p.append("  ").append(k).append(": ").append(brand.get(k)).append("\n"));
            p.append("\n");
        }

        if (config.has("variationHints")) {
            p.append("VARIATION HINTS (follow these):\n");
            config.getJSONArray("variationHints").forEach(h -> p.append("  - ").append(h).append("\n"));
            p.append("\n");
        }

        if (config.has("edgeCaseHints")) {
            p.append("EDGE CASE HINTS (follow these):\n");
            config.getJSONArray("edgeCaseHints").forEach(h -> p.append("  - ").append(h).append("\n"));
            p.append("\n");
        }

        if (config.has("fieldNotes")) {
            p.append("FIELD NOTES:\n");
            JSONObject notes = config.getJSONObject("fieldNotes");
            notes.keys().forEachRemaining(k -> p.append("  ").append(k).append(": ").append(notes.getString(k)).append("\n"));
            p.append("\n");
        }

        p.append("REQUIRED OUTPUT FORMAT (strict JSON, no deviations):\n");
        p.append("{\n");
        p.append("  \"componentName\": \"string\",\n");
        p.append("  \"description\": \"one sentence what this component does\",\n");
        p.append("  \"variations\": [\n");
        p.append("    {\n");
        p.append("      \"id\": \"v1\",\n");
        p.append("      \"label\": \"Short descriptive name\",\n");
        p.append("      \"description\": \"When an author would use this\",\n");
        p.append("      \"fields\": { \"fieldName\": \"value\" }\n");
        p.append("    }\n");
        p.append("  ],\n");
        p.append("  \"edgeCases\": [\n");
        p.append("    {\n");
        p.append("      \"id\": \"e1\",\n");
        p.append("      \"label\": \"Edge case name\",\n");
        p.append("      \"issue\": \"What might break and why\",\n");
        p.append("      \"severity\": \"red\",\n");
        p.append("      \"fields\": { \"fieldName\": \"value\" }\n");
        p.append("    }\n");
        p.append("  ]\n");
        p.append("}\n\n");
        p.append("Rules:\n");
        p.append("- Maximum 5 variations\n");
        p.append("- Maximum 3 edge cases\n");
        p.append("- Use ONLY field names that exist in the dialog XML\n");
        p.append("- Use real asset paths from config if provided\n");
        p.append("- Generate realistic enterprise website content\n");
        p.append("- No lorem ipsum\n");

        return p.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARSE AI / USER JSON RESPONSE
    // Strips markdown fences if Claude wrapped the JSON in them.
    // Ensures required top-level keys exist so page creation never NPEs.
    // ─────────────────────────────────────────────────────────────────────────
    public static JSONObject parseVariationsJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```"))  cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```"))         cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        JSONObject parsed = new JSONObject(cleaned);
        if (!parsed.has("variations"))    parsed.put("variations",    new JSONArray());
        if (!parsed.has("edgeCases"))     parsed.put("edgeCases",     new JSONArray());
        if (!parsed.has("componentName")) parsed.put("componentName", "Component");
        if (!parsed.has("description"))   parsed.put("description",   "");
        return parsed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE SHOWCASE PAGE IN AEM
    // Writes variations + edge cases as JCR nodes under /content/showcase.
    // Each variation gets a title heading node + the actual component node.
    // ─────────────────────────────────────────────────────────────────────────
    public static String createShowcasePage(ResourceResolver resolver,
                                            String resourceType,
                                            JSONObject variations) throws Exception {
        Session session = resolver.adaptTo(Session.class);
        if (session == null) throw new RuntimeException("Could not get JCR Session");

        String componentName = extractComponentName(resourceType);
        String pageNodeName  = componentName.toLowerCase().replaceAll("[^a-z0-9]", "-");
        String pagePath      = SHOWCASE_ROOT + "/" + pageNodeName;

        // Ensure /content/showcase root page exists
        ensureNode(session, SHOWCASE_ROOT, "cq:Page");
        Node showcaseRoot   = session.getNode(SHOWCASE_ROOT);
        Node jcrContentRoot = getOrCreateNode(showcaseRoot, "jcr:content", "cq:PageContent");
        jcrContentRoot.setProperty("jcr:title", "Component Showcase");
        jcrContentRoot.setProperty("sling:resourceType", "wknd/components/page");

        // Remove old page if it exists (re-generate = overwrite)
        if (session.nodeExists(pagePath)) session.getNode(pagePath).remove();

        // Create component showcase page
        Node pageNode    = showcaseRoot.addNode(pageNodeName, "cq:Page");
        Node pageContent = pageNode.addNode("jcr:content", "cq:PageContent");
        pageContent.setProperty("jcr:title",              variations.getString("componentName") + " — Showcase");
        pageContent.setProperty("sling:resourceType",     "wknd/components/page");
        pageContent.setProperty("showcase:resourceType",  resourceType);
        pageContent.setProperty("showcase:description",   variations.getString("description"));
        pageContent.setProperty("showcase:generatedAt",   java.time.Instant.now().toString());

        // Root layout container
        Node root = pageContent.addNode("root", "nt:unstructured");
        root.setProperty("sling:resourceType", "wcm/foundation/components/responsivegrid");

        // ── Variations ───────────────────────────────────────────────────────
        JSONArray varArray = variations.getJSONArray("variations");
        for (int i = 0; i < varArray.length(); i++) {
            JSONObject v = varArray.getJSONObject(i);

            // Label heading
            Node heading = root.addNode("heading_v" + i, "nt:unstructured");
            heading.setProperty("sling:resourceType",           "core/wcm/components/title/v2/title");
            heading.setProperty("jcr:title",                    v.getString("label"));
            heading.setProperty("type",                         "h2");
            heading.setProperty("showcase:variationDescription", v.getString("description"));

            // Component instance with all configured fields
            Node comp = root.addNode("component_v" + i, "nt:unstructured");
            comp.setProperty("sling:resourceType",  resourceType);
            comp.setProperty("showcase:variationId", v.getString("id"));
            setFields(comp, v.getJSONObject("fields"));
        }

        // ── Edge Cases ───────────────────────────────────────────────────────
        if (varArray.length() > 0) {
            Node divider = root.addNode("heading_edge", "nt:unstructured");
            divider.setProperty("sling:resourceType", "core/wcm/components/title/v2/title");
            divider.setProperty("jcr:title", "Edge Cases");
            divider.setProperty("type", "h2");
        }

        JSONArray edgeArray = variations.getJSONArray("edgeCases");
        for (int i = 0; i < edgeArray.length(); i++) {
            JSONObject e = edgeArray.getJSONObject(i);

            Node edgeHeading = root.addNode("heading_e" + i, "nt:unstructured");
            edgeHeading.setProperty("sling:resourceType", "core/wcm/components/title/v2/title");
            edgeHeading.setProperty("jcr:title", "⚠ " + e.getString("label"));
            edgeHeading.setProperty("type", "h3");

            Node edgeComp = root.addNode("edge_e" + i, "nt:unstructured");
            edgeComp.setProperty("sling:resourceType",   resourceType);
            edgeComp.setProperty("showcase:edgeCaseId",  e.getString("id"));
            edgeComp.setProperty("showcase:issue",       e.getString("issue"));
            edgeComp.setProperty("showcase:severity",    e.getString("severity"));
            setFields(edgeComp, e.getJSONObject("fields"));
        }

        session.save();
        log.info("[ShowcaseUtils] Page saved at: {}", pagePath);
        return pagePath;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Writes every key in a JSON fields object to a JCR node as a String property. */
    private static void setFields(Node node, JSONObject fields) {
        fields.keys().forEachRemaining(key -> {
            try {
                node.setProperty(key, fields.getString(key));
            } catch (Exception ex) {
                log.warn("[ShowcaseUtils] Could not set field {}: {}", key, ex.getMessage());
            }
        });
    }

    /** Gets an existing child node or creates it with the given type. */
    public static Node getOrCreateNode(Node parent, String name, String type) throws Exception {
        if (parent.hasNode(name)) return parent.getNode(name);
        return parent.addNode(name, type);
    }

    /** Recursively ensures a JCR path exists, creating missing nodes. */
    public static void ensureNode(Session session, String path, String type) throws Exception {
        if (!session.nodeExists(path)) {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            String nodeName   = path.substring(path.lastIndexOf('/') + 1);
            ensureNode(session, parentPath, "nt:unstructured");
            session.getNode(parentPath).addNode(nodeName, type);
            session.save();
        }
    }

    /** Extracts the component name from a resource type. e.g. "wknd/components/teaser" → "teaser" */
    public static String extractComponentName(String resourceType) {
        return resourceType.substring(resourceType.lastIndexOf('/') + 1);
    }

    /** Builds a standard error JSON response string. */
    public static String error(String message) {
        return new JSONObject().put("status", "error").put("message", message).toString();
    }
}
