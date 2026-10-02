package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.persistence.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * REST transaction entry points. The serialization row must be the FIRST database read:
 * MySQL REPEATABLE READ otherwise retains a pre-lock snapshot for subsequent plain queries.
 * Keep controller/interceptor reads outside this service transaction; do not move settings or
 * permission queries before these locks. No process-local queue or retry-based admission.
 */
@Service @RequiredArgsConstructor @Transactional
public class SupportService {
    @PersistenceContext private EntityManager em;
    private final SupportSettings settings;
    private final AdminPermissionService permissions;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;
    private static boolean controlAccess() { return com.gtcfesk.exchange.control.ControlIdentity.isAccess(); }
    private static Long controlActor() { return com.gtcfesk.exchange.control.ControlIdentity.actorId(); }
    private void audit(String action, Object id) { if(controlAccess()) controlAudit.recordCurrent(action, String.valueOf(id), "", null); }
    private void lockActor(Long admin) {
        if (controlAccess()) { if (em.find(com.gtcfesk.exchange.control.ControlAdmin.class, controlActor(), LockModeType.PESSIMISTIC_WRITE) == null) deny(); }
        else if (com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, admin, LockModeType.PESSIMISTIC_WRITE) == null) deny();
    }

    private <T> TypedQuery<T> tenantQuery(String query, Class<T> result) {
        if (!query.contains(":tenant")) throw new IllegalArgumentException("Missing explicit tenant predicate");
        return em.createQuery(query, result).setParameter("tenant", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }
    private Query tenantQuery(String query) {
        if (!query.contains(":tenant")) throw new IllegalArgumentException("Missing explicit tenant predicate");
        return em.createQuery(query).setParameter("tenant", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private static void deny() { throw new AccessDeniedException("无权访问该会话"); }
    public Long subject(boolean admin) {
        org.springframework.security.core.Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getAuthorities().stream().noneMatch(r -> admin
                ? Arrays.asList("ROLE_ADMIN", "ROLE_SUPER_ADMIN").contains(r.getAuthority()) : "ROLE_USER".equals(r.getAuthority()))) deny();
        return Long.valueOf(a.getName());
    }
    private void permission(String module, String action) { subject(true); permissions.require(module, action); }
    private void enabled(boolean inbox) {
        SupportSettings.Settings s = settings.get();
        if (inbox ? !s.inboxEnabled : !"internal".equals(s.mode)) throw failure(HttpStatus.CONFLICT, inbox ? "站内信已关闭" : "站内客服未开启");
    }
    private SupportConversation conversation(long id, boolean lock) {
        SupportConversation c = lock ? com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportConversation.class, id, LockModeType.PESSIMISTIC_WRITE) : com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportConversation.class, id);
        if (c == null) throw failure(HttpStatus.NOT_FOUND, "会话不存在");
        return c;
    }
    private void readable(SupportConversation c, boolean admin) {
        Long id = subject(admin);
        if (!admin) { if (!id.equals(c.getUserId())) deny(); return; }
        permission("support", "detail");
        if (!id.equals(c.getAdminId()) && !(permissions.isSuper() && permissions.can("support", "audit"))) deny();
    }
    private void writable(SupportConversation c, boolean admin) {
        readable(c, admin); enabled(false);
        if ("CLOSED".equals(c.getStatus())) throw failure(HttpStatus.CONFLICT, "会话已结束");
        if (admin && (!(controlAccess() ? controlActor().equals(c.getControlActorId()) : subject(true).equals(c.getAdminId())) || !"ACTIVE".equals(c.getStatus()))) deny();
        if (admin) permission("support", "reply");
    }
    public SupportConversation start(String ip) { return start(ip, null); }
    public SupportConversation start(String ip, String locale) {
        Long user = subject(false);
        if (com.gtcfesk.exchange.tenant.TenantEntities.find(em, UserAccount.class, user, LockModeType.PESSIMISTIC_WRITE) == null) deny();
        enabled(false);
        List<SupportConversation> open = tenantQuery("from SupportConversation where tenantId=:tenant and activeUserId=:u", SupportConversation.class).setParameter("u", user).getResultList();
        if (!open.isEmpty()) return open.get(0);
        Long recent = tenantQuery("select count(c) from SupportConversation c where tenantId=:tenant and userId=:u and createdAt>:since", Long.class)
            .setParameter("u", user).setParameter("since", Instant.now().minusSeconds(3600)).getSingleResult();
        if (recent >= 10) throw failure(HttpStatus.TOO_MANY_REQUESTS, "会话创建过于频繁，请稍后重试");
        SupportConversation c = new SupportConversation(); c.setUserId(user); c.setActiveUserId(user); c.setClientIp(ip);
        em.persist(c);
        append(c, "SYSTEM", 0L, UUID.randomUUID().toString(), settings.welcome(settings.get(), ip, locale), null);
        return c;
    }
    public List<SupportConversation> sessions(boolean admin, String scope, int page) { return sessions(admin,scope,page,null); }
    public List<SupportConversation> sessions(boolean admin, String scope, int page, String userEmail) {
        Long id = subject(admin);
        if (page < 0 || page > 100000) throw new IllegalArgumentException();
        String filter;
        if (!admin) filter = "userId=:id";
        else {
            permission("support", "");
            if ("queue".equals(scope)) { permission("support", "claim"); filter = "status='WAITING'"; }
            else if ("all".equals(scope)) { if (!permissions.isSuper()) deny(); permission("support", "audit"); filter = "1=1"; }
            else if ("mine".equals(scope)) { filter = controlAccess() ? "controlActorId=:id" : "adminId=:id"; if(controlAccess()) id=controlActor(); }
            else throw new IllegalArgumentException();
        }
        String pattern = admin ? com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail) : null;
        if (pattern != null) filter += " and userId in (select u.id from UserAccount u where u.tenantId=:tenant and lower(u.email) like :userEmail escape '!')";
        TypedQuery<SupportConversation> q = tenantQuery("from SupportConversation where tenantId=:tenant and " + filter + " order by id " + ("queue".equals(scope) ? "asc" : "desc"), SupportConversation.class);
        if (filter.contains(":id")) q.setParameter("id", id);
        if (pattern != null) q.setParameter("userEmail", pattern);
        audit("SUPPORT_LIST", admin ? "admin" : "user");
        return q.setFirstResult(page * 30).setMaxResults(30).getResultList();
    }
    public Map<String,Object> detail(long id, boolean admin, long after) {
        if (after < 0) throw new IllegalArgumentException();
        SupportConversation c = conversation(id, false); readable(c, admin);
        Map<String,Object> out = new LinkedHashMap<>(); out.put("conversation", c);
        out.put("messages", tenantQuery("from SupportMessage where tenantId=:tenant and conversationId=:c and id>:after order by id", SupportMessage.class)
            .setParameter("c", id).setParameter("after", after).setMaxResults(100).getResultList());
        out.put("ahead", "WAITING".equals(c.getStatus()) ? tenantQuery("select count(c) from SupportConversation c where tenantId=:tenant and status='WAITING' and id<:id", Long.class).setParameter("id", id).getSingleResult() : 0L);
        out.put("online", onlineAgents().size());
        audit("SUPPORT_READ", id);
        return out;
    }
    public SupportPresence presence(boolean accepting) {
        if (controlAccess()) { SupportPresence p=new SupportPresence();p.setAccepting(false);return p; }
        Long id = subject(true);
        if (com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, id, LockModeType.PESSIMISTIC_WRITE) == null) deny();
        permission("support", "claim");
        SupportPresence p = com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportPresence.class, id);
        if (p == null) { p = new SupportPresence(); p.setAdminId(id); em.persist(p); }
        p.setAccepting(accepting); p.setHeartbeatAt(Instant.now()); return p;
    }
    private boolean eligible(long id) {
        AdminUser a = com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, id);
        if (a == null || !Boolean.TRUE.equals(a.getEnabled())) return false;
        if ("super_admin".equals(a.getRole())) return true;
        List<String> codes = tenantQuery("select m.menuCode from AdminMenu m, AdminRoleMenu rm, AdminRole r "
            + "where rm.tenantId=:tenant and r.tenantId=:tenant and rm.menuId=m.id and rm.roleId=r.id and r.roleCode=:role and r.status='active' and m.status='active'", String.class)
            .setParameter("role", a.getRole()).getResultList();
        return codes.containsAll(Arrays.asList("support", "support:claim", "support:detail", "support:reply"));
    }
    public List<Map<String,Object>> onlineAgents() {
        List<Map<String,Object>> out = new ArrayList<>();
        for (SupportPresence p : tenantQuery("from SupportPresence where tenantId=:tenant and accepting=true and heartbeatAt>:time", SupportPresence.class)
                .setParameter("time", Instant.now().minusSeconds(65)).getResultList()) {
            if (!eligible(p.getAdminId())) continue;
            Map<String,Object> row = new LinkedHashMap<>(); row.put("id", p.getAdminId()); row.put("name", com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, p.getAdminId()).getAccount()); out.add(row);
        }
        return out;
    }
    public SupportConversation claim(long id) {
        Long admin = subject(true);
        lockActor(admin);
        enabled(false); permission("support", "claim");
        if (controlAccess()) {
            long active=tenantQuery("select count(c) from SupportConversation c where tenantId=:tenant and controlActorId=:actor and status='ACTIVE'",Long.class).setParameter("actor",controlActor()).getSingleResult();
            if(active>=settings.get().capacity)throw failure(HttpStatus.CONFLICT,"接待人数已达上限");
        } else checkCapacity(admin);
        SupportConversation c = conversation(id, true);
        if (!"WAITING".equals(c.getStatus())) throw failure(HttpStatus.CONFLICT, "该会话已被接待，请刷新队列");
        c.setAdminId(controlAccess()?null:admin); c.setControlActorId(controlAccess()?controlActor():null); c.setStatus("ACTIVE"); c.setAcceptedAt(Instant.now());
        append(c, "SYSTEM", admin, UUID.randomUUID().toString(), controlAccess()?"总控管理已接入":"客服已接入", null);audit("SUPPORT_CLAIM",id); return c;
    }
    private void checkCapacity(long admin) {
        // claim and transfer already hold this admin's mutex, acquired before the RR read view.
        // Do not lock all assigned sessions here: opposite-direction transfers would deadlock.
        SupportPresence p = com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportPresence.class, admin);
        if (!eligible(admin) || p == null || !p.isAccepting() || p.getHeartbeatAt().isBefore(Instant.now().minusSeconds(65)))
            throw failure(HttpStatus.CONFLICT, "客服尚未上线或无接待权限");
        long active = tenantQuery("select count(c) from SupportConversation c where tenantId=:tenant and adminId=:id and status='ACTIVE'", Long.class).setParameter("id", admin).getSingleResult();
        if (active >= settings.get().capacity) throw failure(HttpStatus.CONFLICT, "客服接待人数已达上限");
    }
    public SupportConversation transfer(long id, Long target) {
        Long actor = subject(true);
        if (target == null || target.equals(actor)) throw new IllegalArgumentException();
        // Always take the target admin lock before the session lock, same order as claim.
        if (com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, target, LockModeType.PESSIMISTIC_WRITE) == null) throw new IllegalArgumentException();
        enabled(false); permission("support", "transfer");
        checkCapacity(target);
        SupportConversation c = conversation(id, true); readable(c, true);
        if (!"ACTIVE".equals(c.getStatus())) throw failure(HttpStatus.CONFLICT, "只有接待中的会话可转接");
        Long old = c.getAdminId(); c.setAdminId(target); c.setControlActorId(null); c.setAdminReadId(0);audit("SUPPORT_TRANSFER",id);
        append(c, "SYSTEM", subject(true), UUID.randomUUID().toString(), "会话转接：" + old + " -> " + target, null); return c;
    }
    public void close(long id, boolean admin) {
        SupportConversation c = conversation(id, true); readable(c, admin);
        if (admin) permission("support", "close");
        if ("CLOSED".equals(c.getStatus())) return;
        audit("SUPPORT_CLOSE",id);
        c.setStatus("CLOSED"); c.setActiveUserId(null); c.setClosedAt(Instant.now());
        append(c, "SYSTEM", subject(admin), UUID.randomUUID().toString(), admin ? "客服结束会话" : "用户结束会话", null);
    }
    public SupportMessage send(long id, boolean admin, String requestId, String text, MultipartFile image) {
        subject(admin);
        token(requestId); text = text == null ? "" : text.trim();
        if (text.length() > 4000 || (text.isEmpty() && image == null)) throw new IllegalArgumentException();
        SupportConversation c = conversation(id, true); readable(c, admin);
        if (admin && image != null) permission("support", "image");
        Long senderId = admin && controlAccess() ? controlActor() : subject(admin); String sender = admin ? (controlAccess()?"CONTROL":"ADMIN") : "USER";
        List<SupportMessage> previous = tenantQuery("from SupportMessage where tenantId=:tenant and conversationId=:c and sender=:s and senderId=:u and requestId=:r", SupportMessage.class)
            .setParameter("c", id).setParameter("s", sender).setParameter("u", senderId).setParameter("r", requestId).getResultList();
        if (!previous.isEmpty()) {
            SupportMessage saved = previous.get(0);
            if (!saved.getText().equals(text) || saved.isImage() != (image != null)
                || (image != null && !saved.getImageHash().equals(sha256(imageBytes(image)))))
                throw failure(HttpStatus.CONFLICT, "重试编号已用于其他内容");
            return saved; // Safe retry even after a session was closed.
        }
        writable(c, admin);
        long recent = tenantQuery("select count(m) from SupportMessage m where tenantId=:tenant and conversationId=:c and sender=:s and createdAt>:time", Long.class)
            .setParameter("c", id).setParameter("s", sender).setParameter("time", Instant.now().minusSeconds(60)).getSingleResult();
        if (recent >= 30) throw failure(HttpStatus.TOO_MANY_REQUESTS, "发送过快，请稍后重试");
        return append(c, sender, senderId, requestId, text, image == null ? null : imageBytes(image));
    }
    private SupportMessage append(SupportConversation c, String sender, Long senderId, String requestId, String text, byte[] image) {
        if (controlAccess() && senderId != 0) { sender="CONTROL";senderId=controlActor();audit("SUPPORT_APPEND",c.getId()); }
        SupportMessage m = new SupportMessage(); m.setConversationId(c.getId()); m.setSender(sender); m.setSenderId(senderId);
        boolean adminActor = senderId != 0 && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(r -> Arrays.asList("ROLE_ADMIN", "ROLE_SUPER_ADMIN").contains(r.getAuthority()));
        AdminUser actor = adminActor && !controlAccess() ? com.gtcfesk.exchange.tenant.TenantEntities.find(em, AdminUser.class, senderId) : null;
        m.setSenderName("CONTROL".equals(sender)?"总控管理":actor != null ? actor.getAccount() : senderId != 0 ? "user-" + senderId : "system");
        m.setRequestId(requestId); m.setText(text); m.setImage(image != null); m.setImageHash(image == null ? "" : sha256(image));
        m.setPreviousHash(c.getLastHash()); m.setHash(messageHash(m)); em.persist(m);
        if (image != null) { SupportAttachment a = new SupportAttachment(); a.setMessageId(m.getId()); a.setContent(image); em.persist(a); }
        c.setLastHash(m.getHash()); c.setUpdatedAt(Instant.now()); return m;
    }
    public String messageHash(SupportMessage m) {
        try { return sha256(mapper.writeValueAsBytes(Arrays.asList(m.getConversationId(), m.getSender(), m.getSenderId(), m.getSenderName(), m.getRequestId(), m.getText(), m.getImageHash(), m.getCreatedAt().toString(), m.getPreviousHash()))); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    public static String sha256(byte[] data) {
        try { StringBuilder s = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b)); return s.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static byte[] imageBytes(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw failure(HttpStatus.BAD_REQUEST, "图片为空或超过5MB");
        try (InputStream stream = file.getInputStream(); ImageInputStream input = ImageIO.createImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            ImageReader reader = readers.next(); BufferedImage decoded;
            try {
                reader.setInput(input);
                if ((long)reader.getWidth(0) * reader.getHeight(0) > 16000000L) throw new IllegalArgumentException();
                decoded = reader.read(0);
            } finally { reader.dispose(); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (decoded == null || !ImageIO.write(decoded, "png", out) || out.size() > 10 * 1024 * 1024) throw new IllegalArgumentException();
            return out.toByteArray(); // Re-encode, remove metadata and never retain executable/polyglot payloads.
        } catch (IOException | IllegalArgumentException e) { throw failure(HttpStatus.BAD_REQUEST, "请上传有效的 JPG、PNG 或 GIF 图片（最多1600万像素）"); }
    }
    public byte[] attachment(long messageId, boolean admin) {
        SupportMessage m = com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportMessage.class, messageId);
        if (m == null) throw failure(HttpStatus.NOT_FOUND, "图片不存在");
        readable(conversation(m.getConversationId(), false), admin);
        if (admin) permission("support", "image");
        SupportAttachment a = com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportAttachment.class, messageId);
        if (a == null) throw failure(HttpStatus.NOT_FOUND, "图片不存在");
        audit("SUPPORT_ATTACHMENT_READ",messageId);return a.getContent();
    }
    public void read(long id, boolean admin, long through) {
        SupportConversation c = conversation(id, true); readable(c, admin);
        if (admin && (controlAccess() || !subject(true).equals(c.getAdminId()))) return; // Supervisor does not consume an agent's unread messages.
        SupportMessage m = com.gtcfesk.exchange.tenant.TenantEntities.find(em, SupportMessage.class, through);
        if (m == null || !m.getConversationId().equals(id)) throw new IllegalArgumentException();
        if (admin) c.setAdminReadId(Math.max(through, c.getAdminReadId())); else c.setUserReadId(Math.max(through, c.getUserReadId()));
    }
    public Map<String,Object> notifications(boolean admin) {
        Long id = subject(admin); Map<String,Object> out = new LinkedHashMap<>();
        SupportSettings.Settings s = settings.get(); long waiting = 0, chat = 0, latest = 0;
        if ("internal".equals(s.mode) && (!admin || permissions.can("support", "detail"))) {
            String filter = admin ? (controlAccess() ? "c.controlActorId=:id and m.sender='USER'" : "c.adminId=:id and m.sender='USER'") : "c.userId=:id and m.sender in ('ADMIN','CONTROL')";
            if (admin && controlAccess()) id=controlActor();
            String unreadFilter = admin ? " and m.id>c.adminReadId" : " and m.id>c.userReadId";
            String from = " from SupportMessage m, SupportConversation c where m.tenantId=:tenant and c.tenantId=:tenant and m.conversationId=c.id and " + filter;
            chat = tenantQuery("select count(m)" + from + unreadFilter, Long.class).setParameter("id", id).getSingleResult();
            // Arrival watermarks must survive read acknowledgements from another poll or browser tab.
            Long last = tenantQuery("select max(m.id)" + from, Long.class).setParameter("id", id).getSingleResult(); latest = last == null ? 0 : last;
            if (admin && permissions.can("support", "claim")) waiting = tenantQuery("select count(c) from SupportConversation c where tenantId=:tenant and status='WAITING'", Long.class).getSingleResult();
        }
        Long queueLatest = admin && permissions.can("support", "claim") && "internal".equals(s.mode)
            ? tenantQuery("select max(c.id) from SupportConversation c where tenantId=:tenant and status='WAITING'", Long.class).getSingleResult() : Long.valueOf(0);
        out.put("waiting", waiting); out.put("queueLatest", queueLatest == null ? 0 : queueLatest); out.put("chatUnread", chat); out.put("latest", latest);
        long unread = !admin && s.inboxEnabled ? tenantQuery("select count(l) from InboxLetter l where tenantId=:tenant and userId=:u and readAt is null", Long.class).setParameter("u", id).getSingleResult() : 0;
        Long inboxLatest = !admin && s.inboxEnabled ? tenantQuery("select max(l.id) from InboxLetter l where tenantId=:tenant and userId=:u", Long.class).setParameter("u", id).getSingleResult() : Long.valueOf(0);
        out.put("inboxUnread", unread); out.put("inboxLatest", inboxLatest == null ? 0 : inboxLatest);
        out.put("sound", admin ? s.adminSound : s.userSound); out.put("inboxEnabled", s.inboxEnabled); out.put("mode", s.mode);
        return out;
    }
    public Map<String,Object> export(long id) {
        subject(true); if (!permissions.isSuper()) deny();
        SupportConversation c = conversation(id, true);
        permission("support", "export"); readable(c, true);
        List<SupportMessage> rows = tenantQuery("from SupportMessage where tenantId=:tenant and conversationId=:c order by id", SupportMessage.class).setParameter("c", id).setMaxResults(2001).getResultList();
        Map<String,String> images = new LinkedHashMap<>(); String prior = ""; boolean valid = true;
        if (rows.size() > 2000) throw failure(HttpStatus.BAD_REQUEST, "单次导出最多2000条，请通过数据库备份归档完整记录");
        long bytesTotal = 0;
        for (SupportMessage m : rows) {
            valid &= prior.equals(m.getPreviousHash()) && m.getHash().equals(messageHash(m)); prior = m.getHash();
            if (m.isImage()) {
                byte[] bytes = attachment(m.getId(), true); bytesTotal += bytes.length;
                if (bytesTotal > 32 * 1024 * 1024) throw failure(HttpStatus.BAD_REQUEST, "图片总量超过32MB，请通过数据库备份归档完整记录");
                valid &= sha256(bytes).equals(m.getImageHash()); images.put(m.getId().toString(), Base64.getEncoder().encodeToString(bytes));
            }
        }
        Map<String,Object> out = new LinkedHashMap<>(); out.put("version", 1); out.put("exportedAt", Instant.now().toString()); out.put("exportedBy", subject(true));
        out.put("conversation", c); out.put("messages", rows); out.put("imagesPngBase64", images); out.put("chainValid", valid && prior.equals(c.getLastHash()));
        if(controlAccess()){out.put("exportedBy",controlActor());out.put("exportedByType","CONTROL");}audit("SUPPORT_EXPORT",id);
        return out;
    }
    static void token(String id) { if (id == null || !id.matches("[a-zA-Z0-9_-]{16,64}")) throw new IllegalArgumentException(); }
    @Transactional(readOnly = true)
    public List<Map<String,Object>> searchRecipients(String query) {
        permission("inbox", "send"); enabled(true);
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return Collections.emptyList();
        if (text.length() > 128) throw failure(HttpStatus.BAD_REQUEST, "搜索内容不能超过 128 字符");
        long id = -1;
        if (text.matches("[0-9]+")) {
            try { id = Long.parseLong(text); } catch (NumberFormatException ignored) { /* Still search numeric email text. */ }
        }
        String email = "%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Object[]> rows = tenantQuery("select u.id, u.email from UserAccount u where u.tenantId=:tenant and (u.id=:id or lower(u.email) like :email escape '!') order by u.id", Object[].class)
            .setParameter("id", id).setParameter("email", email).setMaxResults(20).getResultList();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String,Object> user = new LinkedHashMap<>(); user.put("id", row[0]); user.put("email", row[1]); result.add(user);
        }
        return result;
    }
    public List<InboxLetter> inbox(boolean admin, int page) { return inbox(admin,page,null); }
    public List<InboxLetter> inbox(boolean admin, int page, String userEmail) {
        Long id = subject(admin); if (page < 0 || page > 100000) throw new IllegalArgumentException();
        if (admin) permission("inbox", ""); else enabled(true);
        String filter = admin ? (permissions.isSuper() ? "1=1" : "adminId=:id") : "userId=:id";
        String pattern = admin ? com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail) : null;
        if (pattern != null) filter += " and userId in (select u.id from UserAccount u where u.tenantId=:tenant and lower(u.email) like :userEmail escape '!')";
        TypedQuery<InboxLetter> q = tenantQuery("from InboxLetter where tenantId=:tenant and " + filter + " order by id desc", InboxLetter.class);
        if (filter.contains(":id")) q.setParameter("id", id);
        if (pattern != null) q.setParameter("userEmail", pattern);
        audit("INBOX_LIST",admin?"admin":"user");
        return q.setFirstResult(page * 30).setMaxResults(30).getResultList();
    }
    public void readLetter(long id) {
        enabled(true); InboxLetter l = com.gtcfesk.exchange.tenant.TenantEntities.find(em, InboxLetter.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (l == null || !subject(false).equals(l.getUserId())) deny();
        if (l.getReadAt() == null) l.setReadAt(Instant.now());
    }
    public void readAllLetters() {
        enabled(true); tenantQuery("update InboxLetter set readAt=:now where tenantId=:tenant and userId=:id and readAt is null")
            .setParameter("now", Instant.now()).setParameter("id", subject(false)).executeUpdate();
    }
    public int sendLetters(String requestId, List<Long> users, String title, String content) {
        Long admin = subject(true); token(requestId);
        if (users == null || users.isEmpty() || users.size() > 200 || users.contains(null) || title == null || content == null
            || title.trim().isEmpty() || content.trim().isEmpty() || title.length() > 120 || content.length() > 4000) throw new IllegalArgumentException();
        lockActor(admin);
        enabled(true); permission("inbox", "send");
        String actorColumn=controlAccess()?"controlActorId":"adminId";Long actualActor=controlAccess()?controlActor():admin;
        Set<Long> ids = new LinkedHashSet<>(users);
        List<InboxLetter> batch = tenantQuery("from InboxLetter where tenantId=:tenant and "+actorColumn+"=:a and requestId=:r", InboxLetter.class)
            .setParameter("a", actualActor).setParameter("r", requestId).getResultList();
        if (!batch.isEmpty()) {
            Set<Long> sentIds = new HashSet<>();
            for (InboxLetter l : batch) {
                sentIds.add(l.getUserId());
                if (!l.getTitle().equals(title.trim()) || !l.getContent().equals(content.trim())) throw failure(HttpStatus.CONFLICT, "重试编号已用于其他内容");
            }
            if (!sentIds.equals(ids)) throw failure(HttpStatus.CONFLICT, "重试时不能变更收件人");
            return 0;
        }
        long exists = tenantQuery("select count(u) from UserAccount u where tenantId=:tenant and id in :ids", Long.class).setParameter("ids", ids).getSingleResult();
        if (exists != ids.size()) throw failure(HttpStatus.BAD_REQUEST, "收件用户不存在，未发送任何站内信");
        int count = 0;
        for (Long user : ids) {
            long sent = tenantQuery("select count(l) from InboxLetter l where tenantId=:tenant and "+actorColumn+"=:a and requestId=:r and userId=:u", Long.class)
                .setParameter("a", actualActor).setParameter("r", requestId).setParameter("u", user).getSingleResult();
            if (sent > 0) continue;
            InboxLetter l = new InboxLetter(); l.setAdminId(controlAccess()?null:admin);l.setControlActorId(controlAccess()?actualActor:null); l.setUserId(user); l.setRequestId(requestId); l.setTitle(title.trim()); l.setContent(content.trim()); em.persist(l); count++;
        }
        audit("INBOX_SEND",requestId);return count;
    }
}
