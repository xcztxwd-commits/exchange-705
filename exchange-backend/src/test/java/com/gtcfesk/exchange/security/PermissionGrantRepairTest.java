package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Synthetic H2 only. Inherits seven baseline tests; seven additional repair tests. */
class PermissionGrantRepairTest extends AdminPermissionIntegrationTest {
    @org.springframework.boot.test.mock.mockito.SpyBean com.gtcfesk.exchange.admin.AgentActionService actionWriter;
    String rolePath() { return "/api/admin/roles/" + role.getId(); }
    Set<Long> roleIds() { return grants.findByTenantIdAndRoleId(1L, role.getId()).stream().map(AdminRoleMenu::getMenuId).collect(Collectors.toSet()); }
    void success(String method, String path, String body, String tk) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse r = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + tk).contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(json.readTree(r.getContentAsString()).path("success").asBoolean(), r.getContentAsString());
    }
    List<String> badIds(long id) {
        return Arrays.asList("{}", "{\"menuIds\":null}", "{\"menuIds\":{}}", "{\"menuIds\":\"x\"}",
            "{\"menuIds\":[\"not-a-menu\"]}", "{\"menuIds\":[\""+id+"\"]}", "{\"menuIds\":[null]}",
            "{\"menuIds\":[true]}", "{\"menuIds\":[{}]}", "{\"menuIds\":[[]]}",
            "{\"menuIds\":["+id+".5]}", "{\"menuIds\":["+id+".0]}", "{\"menuIds\":[1e0]}",
            "{\"menuIds\":[9223372036854775808]}", "{\"menuIds\":[18446744073709551617]}",
            "{\"menuIds\":[0]}", "{\"menuIds\":[-1]}", "{\"menuIds\":["+id+",\"bad\"]}");
    }
    @Test void roleMalformedIdsNeverReplaceExistingGrants() throws Exception {
        grant("durations"); grant("durations:create"); Set<Long> before = roleIds();
        for (String body : badIds(menu("durations").getId())) {
            assertEquals(400, call("POST", rolePath()+"/menus", body, superToken), body);
            assertEquals(before, roleIds(), body);
        }
        long id = menu("durations").getId();
        success("POST",rolePath()+"/menus","{\"menuIds\":["+id+","+id+"]}",superToken);
        assertEquals(Collections.singleton(id), roleIds());
        success("POST",rolePath()+"/menus","{\"menuIds\":[]}",superToken); assertTrue(roleIds().isEmpty());
    }
    UserAccount agent() {
        UserAccount a = new UserAccount(); a.setEmail("grant_"+UUID.randomUUID()+"@example.invalid");
        a.setUserType("agent"); a.setPasswordHash("test-only"); a.setStatus("normal"); return users.saveAndFlush(a);
    }
    Set<Long> agentIds(Long id) { return userMenus.findByTenantIdAndUserId(1L, id).stream().map(UserMenu::getMenuId).collect(Collectors.toSet()); }
    Set<String> agentActions(Long id) { return actions.findByTenantIdAndUserId(1L, id).stream().map(a -> a.getMenuId()+":"+a.getActionCode()).collect(Collectors.toSet()); }
    @Test void agentMalformedMenusAndActionsAreAtomicAndOmissionIsExplicit() throws Exception {
        UserAccount a=agent(); long m=menu("users").getId(); String path="/api/admin/users/"+a.getId()+"/menus";
        success("POST",path,"{\"menuIds\":["+m+"],\"actions\":{\""+m+"\":[\"modify_remark\"]}}",superToken);
        Set<Long> oldMenus=agentIds(a.getId()); Set<String> oldActions=agentActions(a.getId());
        List<String> malformed=new ArrayList<>(badIds(m));
        for(String value:Arrays.asList("null","[]","{\"bad\":[]}","{\"9223372036854775808\":[]}",
            "{\""+m+"\":null}","{\""+m+"\":\"modify_remark\"}","{\""+m+"\":[null]}",
            "{\""+m+"\":[true]}","{\""+m+"\":[12]}","{\""+m+"\":[\"modify_remark\",\"unknown\"]}"))
            malformed.add("{\"menuIds\":["+m+"],\"actions\":"+value+"}");
        for(String body:malformed){
            assertEquals(400,call("POST",path,body,superToken),body);
            assertEquals(oldMenus,agentIds(a.getId()),body); assertEquals(oldActions,agentActions(a.getId()),body);
        }
        AdminMenu button=menu("users:modify_remark");String status=button.getStatus();
        try {
            button.setStatus("disabled");menus.saveAndFlush(button);
            assertEquals(400,call("POST",path,"{\"menuIds\":["+m+"],\"actions\":{\""+m+"\":[\"modify_remark\"]}}",superToken));
            assertEquals(oldMenus,agentIds(a.getId()));assertEquals(oldActions,agentActions(a.getId()));
        } finally {button.setStatus(status);menus.saveAndFlush(button);}
        success("POST",path,"{\"menuIds\":["+m+"]}",superToken);
        assertEquals(oldMenus,agentIds(a.getId())); assertTrue(agentActions(a.getId()).isEmpty());
        success("POST",path,"{\"menuIds\":[],\"actions\":{}}",superToken);
        assertTrue(agentIds(a.getId()).isEmpty()); assertTrue(agentActions(a.getId()).isEmpty());
    }
    @Test void superCanRemoveDisabledOldGrantsButCannotGrantThemAgain() throws Exception {
        grant("durations"); grant("durations:create"); AdminMenu m=menu("durations:create"); String status=m.getStatus();
        try {
            m.setStatus("disabled"); menus.saveAndFlush(m);
            success("POST",rolePath()+"/menus","{\"menuIds\":["+menu("durations").getId()+"]}",superToken);
            assertFalse(roleIds().contains(m.getId()));
            assertEquals(400,call("POST",rolePath()+"/menus","{\"menuIds\":["+menu("durations").getId()+","+m.getId()+"]}",superToken));
            assertEquals(Collections.singleton(menu("durations").getId()),roleIds());
        } finally { m.setStatus(status); menus.saveAndFlush(m); }
    }
    @Test void disabledMenuDoesNotBlockSuperMetadataEditOrUnboundRoleDelete() throws Exception {
        grant("durations"); AdminMenu m=menu("durations"); String status=m.getStatus();
        try {
            m.setStatus("disabled"); menus.saveAndFlush(m);
            success("PUT",rolePath(),"{\"roleName\":\"repaired_"+UUID.randomUUID()+"\",\"status\":\"active\"}",superToken);
            assertEquals(Collections.singleton(m.getId()),roleIds());
            admins.delete(actor); admins.flush();
            success("DELETE",rolePath(),"",superToken); assertFalse(roles.existsByTenantIdAndId(1L, role.getId())); assertTrue(roleIds().isEmpty());
        } finally { m.setStatus(status); menus.saveAndFlush(m); }
    }
    @Test void delegatedCleanupCannotExpandAuthorityOrManageStrongerActiveRole() throws Exception {
        grant("roles"); grant("roles:assign_permission"); grant("roles:edit"); grant("roles:delete");
        AdminRole target=new AdminRole();target.setRoleName("repair_"+UUID.randomUUID());target.setRoleCode("repair_"+UUID.randomUUID());target=roles.saveAndFlush(target);
        AdminMenu dormant=menu("durations");String status=dormant.getStatus();
        AdminRoleMenu g=new AdminRoleMenu();g.setRoleId(target.getId());g.setMenuId(dormant.getId());grants.saveAndFlush(g);
        String path="/api/admin/roles/"+target.getId();
        try {
            assertEquals(403,call("POST",path+"/menus","{\"menuIds\":[]}",token));
            assertEquals(403,call("PUT",path,"{\"roleName\":\"blocked\",\"status\":\"active\"}",token));
            assertEquals(403,call("DELETE",path,null,token));
            dormant.setStatus("disabled");menus.saveAndFlush(dormant);
            assertEquals(403,call("POST",path+"/menus","{\"menuIds\":["+menu("settings").getId()+"]}",token));
            assertEquals(1,grants.findByTenantIdAndRoleId(1L, target.getId()).size());
            success("POST",path+"/menus","{\"menuIds\":[]}",token);
            assertTrue(grants.findByTenantIdAndRoleId(1L, target.getId()).isEmpty());
            assertEquals(400,call("POST",path+"/menus","{\"menuIds\":["+dormant.getId()+"]}",token));
        } finally { dormant.setStatus(status);menus.saveAndFlush(dormant); }
    }
    @Test void agentMixedValidAndUnauthorizedActionPreservesBothTables() throws Exception {
        UserAccount a=agent();long m=menu("users").getId();String path="/api/admin/users/"+a.getId()+"/menus";
        success("POST",path,"{\"menuIds\":["+m+"],\"actions\":{\""+m+"\":[\"modify_remark\"]}}",superToken);
        grant("agents");grant("agents:assign_permission");grant("users");grant("users:modify_remark");
        Set<Long> before=agentIds(a.getId());Set<String> beforeActions=agentActions(a.getId());
        assertEquals(403,call("POST",path,"{\"menuIds\":["+m+"],\"actions\":{\""+m+"\":[\"modify_remark\",\"modify_balance\"]}}",token));
        assertEquals(before,agentIds(a.getId()));assertEquals(beforeActions,agentActions(a.getId()));
    }
    @Test void failureAfterBothTablesWereWrittenRollsBackTheReplacement() throws Exception {
        UserAccount a=agent();long original=menu("users").getId(), replacement=menu("durations").getId();
        String path="/api/admin/users/"+a.getId()+"/menus";
        success("POST",path,"{\"menuIds\":["+original+"],\"actions\":{\""+original+"\":[\"modify_remark\"]}}",superToken);
        Set<Long> oldMenus=agentIds(a.getId());Set<String> oldActions=agentActions(a.getId());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals(Collections.singleton(replacement),agentIds(a.getId()));
            assertTrue(agentActions(a.getId()).isEmpty());
            invocation.callRealMethod(); actions.flush();
            assertEquals(Collections.singleton(replacement+":create"),agentActions(a.getId()));
            throw new IllegalArgumentException("synthetic failure after database writes");
        }).when(actionWriter).assignActions(org.mockito.ArgumentMatchers.eq(a.getId()),org.mockito.ArgumentMatchers.eq(replacement),org.mockito.ArgumentMatchers.anyList());
        assertEquals(400,call("POST",path,"{\"menuIds\":["+replacement+"],\"actions\":{\""+replacement+"\":[\"create\"]}}",superToken));
        assertEquals(oldMenus,agentIds(a.getId()));assertEquals(oldActions,agentActions(a.getId()));
    }
}
