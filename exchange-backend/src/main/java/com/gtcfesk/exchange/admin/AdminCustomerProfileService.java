package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.UploadStorage;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.BalanceAdjustmentService;
import com.gtcfesk.exchange.user.KycIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** One short transaction saves the customer, identity and audited account changes together. */
@Service
@RequiredArgsConstructor
public class AdminCustomerProfileService {
    private final UserAccountRepository users;
    private final AssetAccountRepository assets;
    private final KycRecordRepository identities;
    private final KycIdentityService identity;
    private final AdminPermissionService permissions;
    private final BackendAccess access;
    private final AdminUserService adminUsers;
    private final BalanceAdjustmentService adjustments;
    private final PasswordEncoder passwords;
    private final ObjectMapper mapper;
    private final ControlAuditService audit;
    private final UploadStorage uploads;
    private final com.gtcfesk.exchange.control.BackendLoginRegistry backendLogins;
    private final com.gtcfesk.exchange.repository.BalanceAdjustmentRepository receipts;
    @PersistenceContext private EntityManager em;

    private static final Set<String> PROFILE_FIELDS = new HashSet<>(Arrays.asList(
            "email", "countryCode", "phone", "nickname", "avatarUrl", "annualIncome", "annualIncomeCurrency",
            "remark", "myInviteCode", "parentUserId", "status", "userType", "lastLoginIp", "lastLoginRegion",
            "lastLoginDomain", "lastLoginAt"));

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long userId) {
        permissions.require("users", "detail");
        access.checkUser(userId);
        UserAccount user = users.findByTenantIdAndId(TenantContext.requireTenantId(), userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        requireCurrentOwner(user);
        return response(user);
    }

    @Transactional
    public Map<String, Object> save(Long userId, JsonNode input) {
        permissions.require("users", "detail");
        access.checkUser(userId);
        object(input, new HashSet<>(Arrays.asList("rowVersion", "profile", "kyc", "newPassword", "balances", "expectedBalances", "idempotencyKey", "reason")));
        UserAccount user = users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
        em.refresh(user, javax.persistence.LockModeType.PESSIMISTIC_WRITE);
        requireCurrentOwner(user);
        String requestKey=text(input,"idempotencyKey",64,true);
        if (!requestKey.matches("[A-Za-z0-9_-]{16,64}")) throw new BusinessException("保存请求标识无效");
        Authentication actor=SecurityContextHolder.getContext().getAuthentication();
        String actorType=com.gtcfesk.exchange.control.ControlIdentity.isAccess()?"CONTROL":BackendAccess.agentId()!=null?"AGENT":"ADMIN";
        Long actorId=com.gtcfesk.exchange.control.ControlIdentity.isAccess()?com.gtcfesk.exchange.control.ControlIdentity.actorId():BackendAccess.agentId()!=null?BackendAccess.agentId():Long.valueOf(actor.getName());
        String receiptKey="profile_"+hash(requestKey).substring(0,56);
        String requestHash=hash(userId+":"+actorType+":"+actorId+":"+canonical(input).toString());
        com.gtcfesk.exchange.entity.BalanceAdjustment receipt=receipts.findByTenantIdAndRequestKey(TenantContext.requireTenantId(),receiptKey).orElse(null);
        if (receipt!=null) {
            if (!Objects.equals(receipt.getUserId(),userId)||!Objects.equals(receipt.getRequestHash(),requestHash))
                conflict("保存请求标识已用于不同资料，请刷新后重新编辑");
            return response(user);
        }
        if (!input.path("rowVersion").isIntegralNumber() || !input.path("rowVersion").canConvertToLong() || input.path("rowVersion").longValue() != user.getRowVersion())
            conflict("客户资料已变化，请刷新后重新保存");
        JsonNode profile = input.get("profile");
        Set<String> changed = new LinkedHashSet<>();
        // KYC's shared locking helper refreshes the managed user; run it before editing profile fields.
        if (input.hasNonNull("kyc")) { updateKyc(user, input.get("kyc")); changed.add("kyc"); }
        if (profile != null) {
            object(profile, PROFILE_FIELDS);
            if (profile.size() > 0) {
                permissions.require("users", "edit_profile");
                updateProfile(user, profile, changed);
            }
        }
        if (input.hasNonNull("newPassword") && !text(input, "newPassword", 128, false).isEmpty()) {
            permissions.require("users", "reset_password");
            String password = text(input, "newPassword", 128, true);
            if (password.length() < 6) throw new BusinessException("密码长度至少6位");
            user.setPasswordHash(passwords.encode(password)); user.setCurrentToken(null); changed.add("password");
        }
        if (input.hasNonNull("balances")) { updateBalances(userId, input); changed.add("balances"); }
        if (!changed.isEmpty()) {
            user.setUpdatedAt(LocalDateTime.now());
            users.saveAndFlush(user);
            if (changed.contains("email") && "agent".equals(user.getUserType())) backendLogins.register("AGENT",user.getId(),user.getEmail());
            // Log field names only: no passwords, identity numbers, photos or authentication tokens.
            audit.recordCurrent("CUSTOMER_PROFILE_UPDATE", String.valueOf(userId), String.join(",", changed), text(input, "reason", 500, false));
        }
        // Reuse the immutable receipt store; this marker contains no money delta or private profile values.
        receipt=new com.gtcfesk.exchange.entity.BalanceAdjustment(); receipt.setUserId(userId);receipt.setRequestKey(receiptKey);
        receipt.setRequestHash(requestHash);receipt.setActorType(actorType);receipt.setActorId(actorId);receipt.setChanges("PROFILE_SAVE");receipts.saveAndFlush(receipt);
        return response(user);
    }

    private void updateProfile(UserAccount user, JsonNode patch, Set<String> changed) {
        if (patch.has("remark")&&!Objects.equals(text(patch,"remark",500,false),user.getRemark())) permissions.require("users","modify_remark");
        if (patch.has("myInviteCode")&&!Objects.equals(text(patch,"myInviteCode",32,false),user.getMyInviteCode())) permissions.require("users","modify_invite_code");
        ObjectNode normalized = ((ObjectNode) patch).deepCopy();
        Map<String, Integer> limits = new HashMap<>();
        limits.put("email",128); limits.put("countryCode",32); limits.put("phone",32); limits.put("nickname",50);
        limits.put("avatarUrl",300); limits.put("annualIncomeCurrency",3); limits.put("remark",500); limits.put("myInviteCode",32);
        limits.put("status",20); limits.put("userType",20); limits.put("lastLoginIp",64); limits.put("lastLoginRegion",128); limits.put("lastLoginDomain",255);
        for (Map.Entry<String,Integer> entry : limits.entrySet()) if (patch.has(entry.getKey())) {
            String value = text(patch, entry.getKey(), entry.getValue(), "email".equals(entry.getKey()));
            if (value == null || value.isEmpty()) normalized.putNull(entry.getKey()); else normalized.put(entry.getKey(), value);
        }
        if (patch.has("email")) {
            String email = text(patch,"email",128,true).trim().toLowerCase(Locale.ROOT);
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new BusinessException("邮箱格式无效");
            users.findByTenantIdAndEmail(TenantContext.requireTenantId(),email).filter(other -> !other.getId().equals(user.getId()))
                    .ifPresent(other -> { throw new BusinessException("邮箱已被使用"); });
            if (!email.equalsIgnoreCase(user.getEmail())) user.setCurrentToken(null);
            normalized.put("email",email);
        }
        if (patch.has("myInviteCode") && normalized.hasNonNull("myInviteCode"))
            users.findByTenantIdAndMyInviteCode(TenantContext.requireTenantId(), normalized.get("myInviteCode").asText())
                    .filter(other -> !other.getId().equals(user.getId())).ifPresent(other -> { throw new BusinessException("邀请码已被使用"); });
        if (patch.has("status")) {
            String status = text(patch,"status",20,true);
            if (!Arrays.asList("normal","frozen","banned").contains(status)) throw new BusinessException("状态无效");
            if (!status.equals(user.getStatus())) {
                String action = "frozen".equals(status) ? "freeze_user" : "banned".equals(status) ? "ban_user"
                        : "banned".equals(user.getStatus()) ? "unban_user" : "unfreeze_user";
                permissions.require("users",action); user.setCurrentToken(null);
            }
        }
        if (patch.has("userType")) {
            String type = text(patch,"userType",20,true);
            if (!Arrays.asList("normal","agent").contains(type)) throw new BusinessException("客户类型无效");
            if (!type.equals(user.getUserType())) {
                UpdateUserTypeRequest request = new UpdateUserTypeRequest(); request.setUserId(user.getId()); request.setUserType(type);
                adminUsers.updateUserType(request);
            }
            normalized.remove("userType");
        }
        if (patch.has("parentUserId")) {
            if (BackendAccess.agentId() != null) throw new AccessDeniedException("代理不能更改客户归属");
            Long parent = patch.get("parentUserId").isNull() ? null : positiveLong(patch.get("parentUserId"));
            Set<Long> visited = new HashSet<>(); visited.add(user.getId());
            Long cursor = parent;
            while (cursor != null) {
                if (!visited.add(cursor)) throw new BusinessException("上级关系不能形成循环");
                UserAccount ancestor = users.findByTenantIdAndId(TenantContext.requireTenantId(),cursor).orElseThrow(() -> new BusinessException("上级客户不存在"));
                if (cursor.equals(parent) && !"agent".equals(ancestor.getUserType())) throw new BusinessException("上级必须是代理");
                cursor = ancestor.getParentUserId();
            }
            user.setParentUserId(parent);
            user.setInviteCode(parent == null ? null : users.findByTenantIdAndId(TenantContext.requireTenantId(),parent).get().getMyInviteCode());
            normalized.remove("parentUserId");
        }
        if (patch.hasNonNull("annualIncome")) {
            BigDecimal value = decimal(patch.get("annualIncome"));
            if (value.signum()<0 || value.scale()>2 || value.precision()-value.scale()>12) throw new BusinessException("年收入范围无效");
        }
        if (normalized.hasNonNull("annualIncomeCurrency") && !normalized.get("annualIncomeCurrency").asText().matches("[A-Z]{3}"))
            throw new BusinessException("收入货币请使用三位大写货币代码");
        if (patch.has("lastLoginAt") && patch.hasNonNull("lastLoginAt")) {
            try { LocalDateTime.parse(text(patch,"lastLoginAt",40,true)); } catch (RuntimeException e) { throw new BusinessException("登录时间格式无效"); }
        }
        if (patch.has("avatarUrl") && normalized.hasNonNull("avatarUrl") && !Objects.equals(user.getAvatarUrl(),normalized.get("avatarUrl").asText()))
            normalized.put("avatarUrl", uploads.bindCustomerImage(normalized.get("avatarUrl").asText(), user.getId()));
        try { mapper.readerForUpdating(user).readValue(normalized); }
        catch (java.io.IOException e) { throw new BusinessException("客户信息格式无效"); }
        patch.fieldNames().forEachRemaining(changed::add);
    }

    private void updateKyc(UserAccount user, JsonNode input) {
        object(input,new HashSet<>(Arrays.asList("id","updatedAt","realName","idNumber","idFrontImage","idBackImage","status","reviewRemark")));
        permissions.requireAny("kyc_review:approve_kyc","kyc_review:reject_kyc");
        KycRecord existing = identity.currentForCustomerEdit(user.getId());
        if (existing != null) {
            LocalDateTime expectedTime;
            try { expectedTime=LocalDateTime.parse(text(input,"updatedAt",40,true)); }
            catch (RuntimeException e) { throw new BusinessException("实名版本信息无效，请刷新"); }
            if (!input.path("id").isIntegralNumber() || input.path("id").longValue()!=existing.getId()
                    || !Objects.equals(expectedTime, existing.getUpdatedAt()))
                conflict("实名资料已变化，请刷新后重新保存");
        } else if (input.hasNonNull("id")) conflict("实名资料已变化，请刷新后重新保存");
        String status = text(input,"status",20,true);
        if (!Arrays.asList("NOT_VERIFIED","PENDING","APPROVED","REJECTED").contains(status)) throw new BusinessException("实名状态无效");
        permissions.require("kyc_review", "REJECTED".equals(status) || "NOT_VERIFIED".equals(status) ? "reject_kyc" : "approve_kyc");
        KycRecord record = existing == null ? new KycRecord() : existing;
        record.setUserId(user.getId());
        record.setRealName(text(input,"realName",100,!"NOT_VERIFIED".equals(status)));
        record.setIdNumber(text(input,"idNumber",50,!"NOT_VERIFIED".equals(status)));
        if (record.getRealName()==null) record.setRealName("");
        if (record.getIdNumber()==null) record.setIdNumber("");
        String front = text(input,"idFrontImage",500,false), back = text(input,"idBackImage",500,false);
        record.setIdFrontImage(Objects.equals(front,record.getIdFrontImage())?front:uploads.bindCustomerImage(front,user.getId()));
        record.setIdBackImage(Objects.equals(back,record.getIdBackImage())?back:uploads.bindCustomerImage(back,user.getId()));
        record.setStatus(status); record.setReviewRemark(text(input,"reviewRemark",500,false));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        record.setReviewedBy(auth.getName().matches("[0-9]+")?Long.valueOf(auth.getName()):null);
        record.setReviewedAt(LocalDateTime.now()); identities.saveAndFlush(record);
        user.setKycStatus("APPROVED".equals(status)?"VERIFIED":"NOT_VERIFIED"); user.setKycLevel("APPROVED".equals(status)?1:0);
    }

    private void updateBalances(Long userId, JsonNode input) {
        permissions.require("users","modify_balance");
        if (!permissions.isSuper()) throw new AccessDeniedException("仅超级管理员可以直接设置余额");
        JsonNode targets = input.get("balances"), expected = input.get("expectedBalances");
        Set<String> coins = new HashSet<>(Arrays.asList("FUND","CONTRACT","OPTION"));
        object(targets,coins); object(expected,coins);
        Map<String,BigDecimal> current = new HashMap<>();
        for (AssetAccount account : assets.lockByUserId(userId)) current.put(account.getCoin(),zero(account.getAvailable()));
        UpdateUserBalanceRequest request = new UpdateUserBalanceRequest(); request.setUserId(userId); request.setConfirm(true);
        request.setIdempotencyKey(text(input,"idempotencyKey",64,true)); request.setRemark(text(input,"reason",500,false));
        boolean changed=false;
        for (String coin:coins) if (targets.has(coin)) {
            BigDecimal before=current.getOrDefault(coin,BigDecimal.ZERO), after=decimal(targets.get(coin));
            if (!expected.has(coin) || decimal(expected.get(coin)).compareTo(before)!=0) conflict("账户余额已变化，请刷新后重新保存");
            if (after.signum()<0 || after.scale()>16 || after.precision()-after.scale()>16) throw new BusinessException("余额范围无效");
            if (after.compareTo(before)==0) continue;
            changed=true;
            if ("FUND".equals(coin)) request.setFundBalance(after);
            else if ("CONTRACT".equals(coin)) request.setContractBalance(after); else request.setOptionBalance(after);
        }
        if (changed) adjustments.adjust(request);
    }

    private Map<String,Object> response(UserAccount user) {
        List<AssetAccount> accounts = assets.findByTenantIdAndUserId(TenantContext.requireTenantId(),user.getId());
        BigDecimal total=BigDecimal.ZERO, available=BigDecimal.ZERO;
        for (AssetAccount account:accounts) { available=available.add(zero(account.getAvailable())); total=total.add(zero(account.getAvailable())).add(zero(account.getFrozen())); }
        user.setTotalBalance(total);
        user.setFundBalance(BigDecimal.ZERO); user.setContractBalance(BigDecimal.ZERO); user.setOptionBalance(BigDecimal.ZERO);
        for (AssetAccount account:accounts) {
            if ("FUND".equals(account.getCoin())) user.setFundBalance(zero(account.getAvailable()));
            if ("CONTRACT".equals(account.getCoin())) user.setContractBalance(zero(account.getAvailable()));
            if ("OPTION".equals(account.getCoin())) user.setOptionBalance(zero(account.getAvailable()));
        }
        user.setParentUserEmail(user.getParentUserId()==null?null:users.findByTenantIdAndId(TenantContext.requireTenantId(),user.getParentUserId()).map(UserAccount::getEmail).orElse(null));
        // Serialize through Jackson first so @JsonIgnore remains authoritative, then preserve decimal precision for browsers.
        Map<String,Object> userData=mapper.convertValue(user,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        userData.put("fundBalance",zero(user.getFundBalance()).toPlainString());userData.put("contractBalance",zero(user.getContractBalance()).toPlainString());
        userData.put("optionBalance",zero(user.getOptionBalance()).toPlainString());userData.put("totalBalance",total.toPlainString());
        List<Map<String,Object>> accountData=new ArrayList<>();
        for(AssetAccount account:accounts){Map<String,Object> row=mapper.convertValue(account,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            row.put("available",zero(account.getAvailable()).toPlainString());row.put("frozen",zero(account.getFrozen()).toPlainString());accountData.add(row);}
        Map<String,Object> data=new LinkedHashMap<>(); data.put("user",userData); data.put("accounts",accountData);
        data.put("kyc",identity.latestRecord(user.getId()).orElse(null)); data.put("totalBalance",total.toPlainString()); data.put("availableBalance",available.toPlainString());
        Map<String,Boolean> allowed=new LinkedHashMap<>(); allowed.put("edit",permissions.can("users","edit_profile"));
        allowed.put("resetPassword",permissions.can("users","reset_password"));
        allowed.put("modifyBalance",permissions.isSuper()&&permissions.can("users","modify_balance"));
        allowed.put("editKyc",permissions.can("kyc_review","approve_kyc")||permissions.can("kyc_review","reject_kyc"));
        data.put("permissions",allowed);
        Map<String,Object> response=new LinkedHashMap<>(); response.put("success",true); response.put("data",data); return response;
    }

    static void object(JsonNode node, Set<String> allowed) {
        if (node==null || !node.isObject()) throw new BusinessException("参数必须是对象");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new BusinessException("不允许修改字段："+field); });
    }
    static String text(JsonNode node,String field,int max,boolean required) {
        JsonNode value=node.get(field);
        if (value==null || value.isNull()) { if (required) throw new BusinessException(field+"不能为空"); return null; }
        if (!value.isTextual()) throw new BusinessException(field+"格式无效");
        String result=value.asText();
        if (result.length()>max || result.codePoints().anyMatch(code -> Character.isISOControl(code)&&code!='\n'&&code!='\r'&&code!='\t')) throw new BusinessException(field+"长度或格式无效");
        if (required && result.trim().isEmpty()) throw new BusinessException(field+"不能为空"); return result;
    }
    private static Long positiveLong(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<=0) throw new BusinessException("上级ID格式无效"); return node.longValue();
    }
    private static BigDecimal decimal(JsonNode node) {
        if (node==null || (!node.isNumber()&&!node.isTextual()) || !node.asText().matches("-?\\d{1,32}(?:\\.\\d{1,16})?")) throw new BusinessException("金额格式无效");
        return new BigDecimal(node.asText());
    }
    private static BigDecimal zero(BigDecimal value) { return value==null?BigDecimal.ZERO:value; }
    private static void requireCurrentOwner(UserAccount user) {
        TenantContext.require(user.getTenantId());
        Long agent=BackendAccess.agentId();
        if (agent!=null&&!Objects.equals(agent,user.getParentUserId())) throw new AccessDeniedException("客户归属已变化，无权访问");
    }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static String hash(String value) { return com.gtcfesk.exchange.support.SupportService.sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result=mapper.createObjectNode(); List<String> fields=new ArrayList<>();value.fieldNames().forEachRemaining(fields::add);Collections.sort(fields);
            for(String field:fields) result.set(field,canonical(value.get(field)));return result;
        }
        if(value.isArray()){com.fasterxml.jackson.databind.node.ArrayNode result=mapper.createArrayNode();for(JsonNode element:value)result.add(canonical(element));return result;}
        return value;
    }
}
