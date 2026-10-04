package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the isolated permission fixture, but obtains credentials through real password login. */
class AdminPermissionLoginAuditTest extends AdminPermissionIntegrationTest {
    @Autowired PasswordEncoder encoder;
    private final String password = "Audit-test-only-705!";
    // Each method is one client; use the real Redis limit without sharing every suite's loopback quota.
    private final String clientAddress = "198.18." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1,255)
            + "." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1,255);

    String login(AdminUser user) throws Exception {
        user.setPasswordHash(encoder.encode(password)); admins.saveAndFlush(user);
        return passwordLogin(user);
    }
    String passwordLogin(AdminUser user) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/auth/login")
                .with(request -> { request.setRemoteAddr(clientAddress); return request; })
                .contentType("application/json").content(json.writeValueAsString(
                    new java.util.HashMap<String,String>() {{ put("account",user.getAccount()); put("password",password); }})))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        String result=json.readTree(response.getContentAsString()).path("token").asText();
        assertFalse(result.isEmpty()); return result;
    }
    @Test void realPasswordLoginHonorsRevocationAndDisabledRole() throws Exception {
        grant("durations"); String live=login(actor);
        assertEquals(200,call("GET","/api/admin/durations",null,live));
        role.setStatus("inactive");roles.saveAndFlush(role);
        assertEquals(403,call("GET","/api/admin/durations",null,live));
        role.setStatus("active");roles.saveAndFlush(role);
        assertEquals(200,call("GET","/api/admin/durations",null,live));
        grants.deleteAllByTenantId(1L, grants.findByTenantIdAndRoleId(1L, role.getId()));grants.flush();
        assertEquals(403,call("GET","/api/admin/durations",null,live));
    }
    @Test void secondLoginDisabledAccountAndPasswordResetInvalidateSessions() throws Exception {
        grant("durations"); String first=login(actor), second=passwordLogin(actor);
        assertEquals(401,call("GET","/api/admin/durations",null,first));
        assertEquals(200,call("GET","/api/admin/durations",null,second));
        AdminUser fresh=admins.findByTenantIdAndId(1L, actor.getId()).get(); fresh.setEnabled(false); fresh=admins.saveAndFlush(fresh);
        assertEquals(401,call("GET","/api/admin/durations",null,second));
        fresh.setEnabled(true); fresh.setPasswordHash(encoder.encode(password+"changed")); admins.saveAndFlush(fresh);
        assertEquals(401,call("GET","/api/admin/durations",null,second));
    }
    @Test void multipleRealAccountsShareRoleButCannotCrossRole() throws Exception {
        grant("durations"); AdminUser peer=admin(role.getRoleCode()), outsider=admin("nonexistent_role");
        String a=login(actor), b=login(peer), c=login(outsider);
        for(String tk:new String[]{a,b}) {
            assertEquals(200,call("GET","/api/admin/durations",null,tk));
            assertEquals(403,call("POST","/api/admin/durations","{}",tk));
            assertEquals(403,call("GET","/api/admin/admins",null,tk));
        }
        assertEquals(403,call("GET","/api/admin/durations",null,c));
        assertEquals(0,getJson("/api/admin/menus/current",c).path("menus").size());
    }
    @Test void assignedRolesProtectBoundDeletionAndUnknownOrOrphanGrants() throws Exception {
        grant("durations");
        String path="/api/admin/roles/"+role.getId();
        org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).header("Authorization","Bearer "+superToken)).andReturn().getResponse();
        assertFalse(json.readTree(response.getContentAsString()).path("success").asBoolean());
        assertTrue(roles.findByTenantIdAndId(1L, role.getId()).isPresent());
        for(String body:new String[]{"{\"menuIds\":[-123456]}","{\"menuIds\":["+menu("durations:create").getId()+"]}"}) {
            response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/menus")
                .header("Authorization","Bearer "+superToken).contentType("application/json").content(body)).andReturn().getResponse();
            assertFalse(json.readTree(response.getContentAsString()).path("success").asBoolean());
            assertEquals(1,grants.findByTenantIdAndRoleId(1L, role.getId()).size());
        }
    }
}
