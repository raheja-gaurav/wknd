package com.adobe.aem.guides.wknd.core.servlets;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import java.util.Iterator;

/**
 * Shared utilities for the Showcase servlet pair.
 *
 * ShowcasePromptServlet      (/bin/showcase/prompt)     — reads dialog, builds prompt
 * ShowcasePageCreatorServlet (/bin/showcase/createPage)  — imports page JSON into JCR
 */
public final class ShowcaseUtils {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseUtils.class);

    public static final String SHOWCASE_ROOT = "/content/showcase";

    private ShowcaseUtils() {}

    // ─────────────────────────────────────────────────────────────────────────
    // READ DIALOG AS JSON from JCR node tree
    // ─────────────────────────────────────────────────────────────────────────
    public static String readDialogXML(ResourceResolver resolver, String resourceType) {
        try {
            String dialogPath = "/apps/" + resourceType + "/cq:dialog";
            Resource dialogResource = resolver.getResource(dialogPath);

            if (dialogResource == null) {
                dialogPath = "/apps/" + resourceType + "/_cq_dialog";
                dialogResource = resolver.getResource(dialogPath);
            }

            if (dialogResource == null) {
                log.warn("[ShowcaseUtils] Dialog not found for: {}", resourceType);
                return null;
            }

            JSONObject dialogJson = resourceToJson(dialogResource);
            String json = dialogJson.toString(2);
            log.info("[ShowcaseUtils] Dialog JSON for {} — {} chars", resourceType, json.length());
            return json;

        } catch (Exception e) {
            log.error("[ShowcaseUtils] Failed to read dialog for {}: {}", resourceType, e.getMessage());
            return null;
        }
    }

    private static JSONObject resourceToJson(Resource resource) {
        JSONObject obj = new JSONObject();
        ValueMap props = resource.getValueMap();

        for (String key : props.keySet()) {
            if (key.startsWith("jcr:created") || key.startsWith("jcr:lastModified")
                    || key.equals("jcr:uuid")) {
                continue;
            }
            Object val = props.get(key);
            if (val instanceof String[]) {
                obj.put(key, new JSONArray((String[]) val));
            } else if (val != null) {
                obj.put(key, val.toString());
            }
        }

        for (Resource child : resource.getChildren()) {
            obj.put(child.getName(), resourceToJson(child));
        }

        return obj;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ SHOWCASE CONFIG (optional, for future use)
    // ─────────────────────────────────────────────────────────────────────────
    public static JSONObject readShowcaseConfig(ResourceResolver resolver, String resourceType) {
        return new JSONObject();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUILD AI PROMPT
    // Instructs Claude to return a full AEM page-importable JSON that can
    // be written directly into JCR via the content import API.
    // ─────────────────────────────────────────────────────────────────────────
    public static String buildPrompt(String dialogJson, JSONObject config, String resourceType) {
        String componentName = extractComponentName(resourceType);

        StringBuilder p = new StringBuilder();

        p.append("You are an AEM (Adobe Experience Manager) expert content architect.\n\n");

        p.append("TASK: Generate a showcase page for the AEM component below. ");
        p.append("The page should demonstrate 5 realistic variations of this component ");
        p.append("so that authors and developers can see every possible usage.\n\n");

        p.append("COMPONENT RESOURCE TYPE: ").append(resourceType).append("\n");
        p.append("COMPONENT NAME: ").append(componentName).append("\n\n");

        p.append("COMPONENT DIALOG JSON (cq:dialog node tree — these are ALL the fields an author can configure):\n");
        p.append("```json\n").append(dialogJson).append("\n```\n\n");

        // ── Output format instructions ──────────────────────────────────────
        p.append("RETURN a single JSON array containing exactly ONE object — a complete AEM page structure.\n");
        p.append("This JSON will be imported directly into AEM's JCR repository.\n\n");

        p.append("EXACT STRUCTURE (follow this precisely):\n");
        p.append("```json\n");
        p.append("[\n");
        p.append("  {\n");
        p.append("    \"jcr:primaryType\": \"cq:Page\",\n");
        p.append("    \"jcr:content\": {\n");
        p.append("      \"jcr:primaryType\": \"cq:PageContent\",\n");
        p.append("      \"jcr:title\": \"").append(componentName).append(" Showcase\",\n");
        p.append("      \"cq:template\": \"/conf/wknd/settings/wcm/templates/landing-page-template\",\n");
        p.append("      \"jcr:description\": \"Auto-generated showcase of all ").append(componentName).append(" component variations\",\n");
        p.append("      \"sling:resourceType\": \"wknd/components/page\",\n");
        p.append("      \"root\": {\n");
        p.append("        \"jcr:primaryType\": \"nt:unstructured\",\n");
        p.append("        \"sling:resourceType\": \"wknd/components/container\",\n");
        p.append("        \"container\": {\n");
        p.append("          \"jcr:primaryType\": \"nt:unstructured\",\n");
        p.append("          \"layout\": \"responsiveGrid\",\n");
        p.append("          \"sling:resourceType\": \"wknd/components/container\",\n");
        p.append("          ... YOUR COMPONENTS GO HERE ...\n");
        p.append("        }\n");
        p.append("      }\n");
        p.append("    }\n");
        p.append("  }\n");
        p.append("]\n");
        p.append("```\n\n");

        // ── Component generation rules ──────────────────────────────────────
        p.append("INSIDE the \"container\" object, generate these child nodes:\n\n");

        p.append("For EACH variation (generate 5 variations):\n");
        p.append("  1. A heading node: \"heading_N\" with:\n");
        p.append("     - \"jcr:primaryType\": \"nt:unstructured\"\n");
        p.append("     - \"jcr:title\": \"Descriptive Variation Name\"\n");
        p.append("     - \"type\": \"h2\"\n");
        p.append("     - \"sling:resourceType\": \"wknd/components/title\"\n\n");

        p.append("  2. A description node: \"desc_N\" with:\n");
        p.append("     - \"jcr:primaryType\": \"nt:unstructured\"\n");
        p.append("     - \"text\": \"<p><em>Brief explanation of when to use this variation</em></p>\"\n");
        p.append("     - \"sling:resourceType\": \"wknd/components/text\"\n");
        p.append("     - \"textIsRich\": \"true\"\n\n");

        p.append("  3. The actual component node: \"component_N\" with:\n");
        p.append("     - \"jcr:primaryType\": \"nt:unstructured\"\n");
        p.append("     - \"sling:resourceType\": \"").append(resourceType).append("\"\n");
        p.append("     - All relevant dialog fields populated with realistic content\n\n");

        p.append("  4. A separator node: \"separator_N\" with:\n");
        p.append("     - \"jcr:primaryType\": \"nt:unstructured\"\n");
        p.append("     - \"sling:resourceType\": \"wknd/components/separator\"\n\n");

        p.append("FIELD RULES:\n");
        p.append("- Look at the dialog JSON for field \"name\" properties (e.g. \"./jcr:title\", \"./pretitle\")\n");
        p.append("- Strip the leading \"./\" prefix (use \"jcr:title\" not \"./jcr:title\")\n");
        p.append("- For rich text fields, wrap content in <p> tags\n");
        p.append("- For boolean fields (checkboxes), use string \"true\" or \"false\"\n");
        p.append("- For image fields (fileReference), use real WKND DAM paths like:\n");
        p.append("  /content/dam/wknd-shared/en/magazine/western-australia/western-australia-by-702010-702010.jpg\n");
        p.append("  /content/dam/wknd-shared/en/magazine/san-diego-surf-spots/adobestock-272184938.jpeg\n");
        p.append("  /content/dam/wknd-shared/en/magazine/arctic-702010-702010/aloha-702010-702010.jpg\n");
        p.append("- For link fields, use paths like /content/wknd/language-masters/en/about-us\n\n");

        p.append("VARIATION STRATEGY — make each variation meaningfully different:\n");
        p.append("  Variation 0: Full-featured — ALL fields populated (hero/showcase use case)\n");
        p.append("  Variation 1: Minimal — Only required fields (clean/simple use case)\n");
        p.append("  Variation 2: With actions/CTAs enabled if the component supports it\n");
        p.append("  Variation 3: Content inherited from linked page (if supported)\n");
        p.append("  Variation 4: Edge case — very long text, missing optional fields, etc.\n\n");

        p.append("CONTENT RULES:\n");
        p.append("- Generate realistic enterprise website content (travel, outdoor adventure theme for WKND)\n");
        p.append("- No lorem ipsum — use real-sounding headlines and descriptions\n");
        p.append("- Each variation must have different content, not just different field combinations\n\n");

        p.append("Return ONLY the JSON array. No markdown fences. No explanation. Just valid JSON.\n");

        return p.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT PAGE JSON INTO JCR
    // Takes the full page-importable JSON from Claude and writes it into JCR.
    // The JSON is the array format: [ { "jcr:primaryType": "cq:Page", ... } ]
    // ─────────────────────────────────────────────────────────────────────────
    public static String createShowcasePage(ResourceResolver resolver,
                                            String resourceType,
                                            String pageJson) throws Exception {
        Session session = resolver.adaptTo(Session.class);
        if (session == null) throw new RuntimeException("Could not get JCR Session");

        String componentName = extractComponentName(resourceType);
        String pageNodeName  = componentName.toLowerCase().replaceAll("[^a-z0-9]", "-");
        String pagePath      = SHOWCASE_ROOT + "/" + pageNodeName;

        // Ensure /content/showcase exists
        ensureNode(session, SHOWCASE_ROOT, "cq:Page");
        Node showcaseRoot = session.getNode(SHOWCASE_ROOT);
        if (!showcaseRoot.hasNode("jcr:content")) {
            Node rc = showcaseRoot.addNode("jcr:content", "cq:PageContent");
            rc.setProperty("jcr:title", "Component Showcase");
            rc.setProperty("sling:resourceType", "wknd/components/page");
            session.save();
        }

        // Remove old page if exists
        if (session.nodeExists(pagePath)) {
            session.getNode(pagePath).remove();
            session.save();
        }

        // Parse the JSON array — take the first element
        JSONArray arr = new JSONArray(pageJson);
        JSONObject pageObj = arr.getJSONObject(0);

        // Create the page node tree recursively
        writeJsonToJcr(showcaseRoot, pageNodeName, pageObj, session);

        session.save();
        log.info("[ShowcaseUtils] Page imported at: {}", pagePath);
        return pagePath;
    }

    /**
     * Recursively writes a JSONObject as a JCR node with properties and children.
     */
    private static void writeJsonToJcr(Node parent, String nodeName, JSONObject json, Session session) throws Exception {
        // Determine node type
        String primaryType = json.optString("jcr:primaryType", "nt:unstructured");

        Node node;
        if (parent.hasNode(nodeName)) {
            node = parent.getNode(nodeName);
        } else {
            node = parent.addNode(nodeName, primaryType);
        }

        // Set properties
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = json.get(key);

            // Skip — child nodes are JSONObjects, handle them separately
            if (val instanceof JSONObject) {
                continue;
            }

            // Skip jcr:primaryType since we set it during node creation
            if ("jcr:primaryType".equals(key)) {
                continue;
            }

            // Handle arrays (e.g. cq:styleIds)
            if (val instanceof JSONArray) {
                JSONArray jarr = (JSONArray) val;
                String[] values = new String[jarr.length()];
                for (int i = 0; i < jarr.length(); i++) {
                    values[i] = jarr.optString(i, "");
                }
                node.setProperty(key, values);
                continue;
            }

            // Everything else is a string property
            node.setProperty(key, val.toString());
        }

        // Recurse into child nodes (JSONObject values)
        keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = json.get(key);
            if (val instanceof JSONObject) {
                writeJsonToJcr(node, key, (JSONObject) val, session);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    public static void ensureNode(Session session, String path, String type) throws Exception {
        if (!session.nodeExists(path)) {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            String nodeName   = path.substring(path.lastIndexOf('/') + 1);
            ensureNode(session, parentPath, "nt:unstructured");
            session.getNode(parentPath).addNode(nodeName, type);
            session.save();
        }
    }

    public static String extractComponentName(String resourceType) {
        return resourceType.substring(resourceType.lastIndexOf('/') + 1);
    }

    public static String error(String message) {
        return new JSONObject().put("status", "error").put("message", message).toString();
    }
}
