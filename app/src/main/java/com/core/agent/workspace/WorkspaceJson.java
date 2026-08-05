package com.core.agent.workspace;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 工作区 JSON 解析/指针/类型 helper（重构计划 4.5 GameWorkspaceGateway 拆分，阶段 130）。
 *
 * 自 GameWorkspaceGateway 抽取的高内聚 JSON 工具：根节点解析（对象/数组 + 尾随内容校验）、
 * JSON Pointer 令牌解码（~0/~1 转义）、值类型判定。行为与迁移前逐字等价。
 */
final class WorkspaceJson {
    private WorkspaceJson() { }

    static Object parseRoot(String text) throws Exception {
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
            throw new IllegalArgumentException("JSON 根节点必须是对象或数组");
        }
        if (tokener.nextClean() != 0) throw new IllegalArgumentException("JSON 根节点后存在多余内容");
        return value;
    }

    static String decodePointerToken(String raw) {
        StringBuilder decoded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '~') { decoded.append(c); continue; }
            if (++i >= raw.length()) throw new IllegalArgumentException("JSON Pointer 转义格式错误");
            char escaped = raw.charAt(i);
            if (escaped == '0') decoded.append('~');
            else if (escaped == '1') decoded.append('/');
            else throw new IllegalArgumentException("JSON Pointer 转义格式错误");
        }
        return decoded.toString();
    }

    static String jsonType(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return "string";
    }
}
