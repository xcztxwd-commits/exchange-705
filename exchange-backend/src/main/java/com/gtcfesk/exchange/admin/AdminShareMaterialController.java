package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/admin/share-materials") @RequiredArgsConstructor
public class AdminShareMaterialController {
    private final SystemConfigService configs;
    private final TenantRepository tenants;
    private final AdminPermissionService permissions;
    private final TenantPolicyService policy;
    private final ControlAuditService audit;
    private ObjectNode library() { return SystemConfigService.shareMaterials(configs.getConfigValue(SystemConfigService.SHARE_MATERIALS_KEY)); }

    @GetMapping @AdminPermission(menu="share_templates", action="")
    public Object list() {
        permissions.require("share_templates", "");
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : library().path("materials")) if (!item.path("deleted").asBoolean()) result.add(item);
        Collections.reverse(result); return result;
    }
    public static class Input { public String name; public JsonNode layer; }
    @Transactional @PostMapping @AdminPermission(menu="share_templates", action="save")
    public Object save(@RequestBody Input input) {
        permissions.require("share_templates", "save");
        policy.requireConfigChange(SystemConfigService.SHARE_MATERIALS_KEY, null);
        if (input.name == null || input.name.trim().isEmpty() || input.name.trim().length() > 80) throw new BusinessException("素材名称应为 1–80 个字");
        if (input.layer == null) throw new BusinessException("素材不能为空");
        ShareTemplateDesignValidator.validateDecoration(input.layer, 2160, 2160);
        tenants.lock(TenantContext.requireTenantId()).orElseThrow(() -> new BusinessException("租户不存在"));
        ObjectNode root = library(), item = root.objectNode();
        item.put("id", "material-" + UUID.randomUUID()); item.put("name", input.name.trim()); item.put("deleted", false); item.set("layer", input.layer.deepCopy());
        ((ArrayNode) root.path("materials")).add(item);
        configs.saveConfig(SystemConfigService.SHARE_MATERIALS_KEY, root.toString(), "持仓分享模板素材库");
        audit.recordCurrent("SHARE_MATERIAL_CREATE", item.path("id").asText(), "validated geometry or tenant image reference", null);
        return item;
    }
    @Transactional @DeleteMapping("/{id}") @AdminPermission(menu="share_templates", action="save")
    public Object remove(@PathVariable String id) {
        permissions.require("share_templates", "save");
        policy.requireConfigChange(SystemConfigService.SHARE_MATERIALS_KEY, null);
        tenants.lock(TenantContext.requireTenantId()).orElseThrow(() -> new BusinessException("租户不存在"));
        ObjectNode root = library(); boolean found = false;
        // ponytail: bounded 60 KB soft-removal history keeps copied draft images readable; use a table if the library outgrows the config column.
        for (JsonNode item : root.path("materials")) if (id.equals(item.path("id").asText()) && !item.path("deleted").asBoolean()) { ((ObjectNode) item).put("deleted", true); found = true; }
        if (!found) throw new BusinessException("素材不存在");
        configs.saveConfig(SystemConfigService.SHARE_MATERIALS_KEY, root.toString(), "持仓分享模板素材库");
        audit.recordCurrent("SHARE_MATERIAL_REMOVE", id, "soft removal; template and draft copies preserved", null);
        return Collections.singletonMap("deleted", true);
    }
}
