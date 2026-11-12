package com.bemodel.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M-16 Agent 只读投影：按 {@link ComputeFieldCatalog} 的正向清单复制字段。
 *
 * <p>不做“删掉黑名单字段”的减法：目录里没有的字段、敏感字段和整段原始报文都不会出现在结果里。
 * 这样新增 DTO 字段时默认不会泄露，必须显式登记后才可能进入 Agent。</p>
 */
@Service
@Profile("compute")
final class ComputeAgentView {
    private final ObjectMapper json;

    ComputeAgentView(ObjectMapper json) {
        this.json = json;
    }

    JsonNode project(String resource, Object value) {
        ObjectNode result = json.createObjectNode();
        if (value == null) {
            return result;
        }
        JsonNode source = json.valueToTree(value);
        for (ComputeFieldPolicy policy : ComputeFieldCatalog.forResource(resource)) {
            if (policy.agentVisible()) {
                copy(source, result, policy.path().split("\\."), 0);
            }
        }
        pruneEmptyObjects(result);
        return result;
    }

    ObjectNode requestDetail(ComputeRequestService.Detail detail) {
        ObjectNode result = json.createObjectNode();
        result.set("request", project(ComputeFieldCatalog.REQUEST, detail.request()));
        ArrayNode jobs = result.putArray("jobs");
        detail.jobs().forEach(job -> jobs.add(project(ComputeFieldCatalog.JOB, job)));
        if (detail.currentJob() != null) {
            result.set("currentJob", project(ComputeFieldCatalog.JOB, detail.currentJob()));
        }
        result.put("authorizesExecution", false);
        return result;
    }

    List<JsonNode> actionResults(List<Map<String, Object>> rows) {
        List<JsonNode> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(project(ComputeFieldCatalog.ACTION_RESULT, row));
        }
        return result;
    }

    private void copy(JsonNode source, ObjectNode target, String[] parts, int index) {
        if (source == null || !source.isObject() || index >= parts.length) {
            return;
        }
        String raw = parts[index];
        boolean array = raw.endsWith("[]");
        String name = array ? raw.substring(0, raw.length() - 2) : raw;
        JsonNode child = source.get(name);
        if (child == null || child.isNull()) {
            return;
        }
        boolean last = index == parts.length - 1;
        if (array) {
            if (!child.isArray()) {
                return;
            }
            ArrayNode output = target.withArray(name);
            for (int i = 0; i < child.size(); i++) {
                JsonNode element = child.get(i);
                if (element == null || element.isNull()) {
                    continue;
                }
                while (output.size() <= i) {
                    output.addObject();
                }
                JsonNode slot = output.get(i);
                if (slot.isObject()) {
                    copy(element, (ObjectNode) slot, parts, index + 1);
                }
            }
        } else if (last) {
            target.set(name, child.deepCopy());
        } else if (child.isObject()) {
            ObjectNode nested = target.has(name) && target.get(name).isObject()
                    ? (ObjectNode) target.get(name) : target.putObject(name);
            copy(child, nested, parts, index + 1);
        }
    }

    /** 只清掉数组里因字段全为空而留下的空对象，不删除合法的空数组（如 reasons=[]）。 */
    private static void pruneEmptyObjects(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> empty = new ArrayList<>();
            object.fields().forEachRemaining(entry -> {
                pruneEmptyObjects(entry.getValue());
                if (entry.getValue().isObject() && entry.getValue().isEmpty()) {
                    empty.add(entry.getKey());
                }
            });
            empty.forEach(object::remove);
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = array.size() - 1; i >= 0; i--) {
                JsonNode element = array.get(i);
                pruneEmptyObjects(element);
                if (element.isObject() && element.isEmpty()) {
                    array.remove(i);
                }
            }
        }
    }
}
