package com.gtcfesk.exchange.control;
import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ControlTablePreferenceTest {
 ObjectMapper mapper=new ObjectMapper();ControlTablePreferenceRepository repo=mock(ControlTablePreferenceRepository.class);ControlAuditService audit=mock(ControlAuditService.class);
 ControlTablePreferenceController controller=new ControlTablePreferenceController(repo,mapper,audit);
 void login(long actor,String role,Long tenant,String session){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("display-name-not-a-key",null,Collections.singletonList(new SimpleGrantedAuthority(role)));a.setDetails(new ControlIdentity(actor,tenant,session));SecurityContextHolder.getContext().setAuthentication(a);}
 @AfterEach void clean(){SecurityContextHolder.clearContext();TenantContext.clear();}
 @Test void persistedKeysAreActorAndTableNotSessionOrTenant() throws Exception{
  Map<String,ControlTablePreference> data=new HashMap<>();when(repo.findById(anyString())).thenAnswer(c->Optional.ofNullable(data.get(c.getArgument(0))));when(repo.saveAndFlush(any())).thenAnswer(c->{ControlTablePreference p=c.getArgument(0);data.put(p.getId(),p);return p;});
  JsonNode config=mapper.readTree("[{\"id\":\"account\",\"visible\":true,\"fixed\":\"right\"}]");
  login(11,"ROLE_CONTROL",null,null);controller.save("control.accounts",config);assertEquals(config,controller.get("control.accounts"));assertEquals(0,controller.get("control.audit").size());
  login(12,"ROLE_CONTROL",null,null);assertEquals(0,controller.get("control.accounts").size());
  login(11,"ROLE_CONTROL",null,null);assertEquals(config,controller.get("control.accounts"));assertEquals(11L,data.get("11:control.accounts").getActorId());verify(audit).record(eq(11L),isNull(),isNull(),eq("CONTROL_TABLE_PREFERENCES"),eq("control.accounts"),eq("SUCCESS"),anyString(),isNull());
 }
 @Test void forbidsAccessAndOrdinaryCredentialsAndInjectedActor() throws Exception{
  JsonNode config=mapper.readTree("[{\"id\":\"x\",\"visible\":true,\"fixed\":\"\",\"actorId\":12}]");
  login(11,"ROLE_CONTROL",null,null);assertThrows(ResponseStatusException.class,()->controller.save("accounts",config));assertThrows(ResponseStatusException.class,()->controller.get("../accounts"));
  login(11,"ROLE_CONTROL",1L,"access-a");assertThrows(AccessDeniedException.class,()->controller.get("accounts"));
  login(11,"ROLE_ADMIN",null,null);assertThrows(AccessDeniedException.class,()->controller.save("accounts",mapper.createArrayNode()));
  login(11,"ROLE_CONTROL",null,null);try(TenantContext.Scope ignored=TenantContext.open(1L)){assertThrows(AccessDeniedException.class,()->controller.get("accounts"));}
  SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->controller.get("accounts"));verifyNoInteractions(repo);
 }
 @Test void rejectsHiddenAllDuplicatesAndOversize(){login(11,"ROLE_CONTROL",null,null);
  for(String body:Arrays.asList("null","{}","[{\"id\":\"x\",\"visible\":false,\"fixed\":\"\"}]","[{\"id\":\"x\",\"visible\":true,\"fixed\":\"bad\"}]","[{\"id\":\"x\",\"visible\":true,\"fixed\":\"\"},{\"id\":\"x\",\"visible\":true,\"fixed\":\"\"}]"))assertThrows(ResponseStatusException.class,()->controller.save("accounts",mapper.readTree(body)));
  com.fasterxml.jackson.databind.node.ArrayNode large=mapper.createArrayNode();for(int i=0;i<151;i++)large.addObject().put("id","x"+i).put("visible",true).put("fixed","");assertThrows(ResponseStatusException.class,()->controller.save("accounts",large));verifyNoInteractions(repo);
 }
}
