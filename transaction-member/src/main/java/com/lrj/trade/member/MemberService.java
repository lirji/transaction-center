package com.lrj.trade.member;

import com.lrj.trade.core.*;
import java.util.List;
import com.lrj.trade.member.mapper.MemberMapper;
import com.lrj.trade.member.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 业务会员服务，不存储密码且不替代登录系统。 */
@Service
public class MemberService implements MemberTradingPort {
    private final MemberMapper mapper;
    private final Commands commands;
    private final Outbox outbox;
    private final AuditWriter audit;
    private final java.time.Clock clock;
    /** 注册和消费空事实使用相同事务，防止会员存在而首单判断无锁定行。 */
    public MemberService(MemberMapper mapper, Commands commands, Outbox outbox, AuditWriter audit,java.time.Clock clock) {
        this.mapper=mapper; this.commands = commands; this.outbox = outbox; this.audit=audit; this.clock=clock;
    }
    /** 注册结果只返回不可变编号；业务注册时间与首付共用注入时钟，避免资格时点使用两套时间源。 */
    public String register(Actor actor, String displayName) {
        Checks.text(displayName, 100);
        return commands.run(actor, "member.register", actor.key(), actor.key(), () -> {
            var registeredAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            mapper.insertMember(actor.tenantId(),actor.key(),actor.issuer(),actor.subject(),displayName,registeredAt);
            long id = mapper.findIdByIdentity(actor.tenantId(),actor.key());
            mapper.insertTradeFact(actor.tenantId(),id);
            outbox.memberRegistered(actor.tenantId(),id,registeredAt);
            return Long.toString(id);
        });
    }
    /** 仅允许查询可信身份自己的会员，外部会员编号不能覆盖身份。 */
    public Member me(Actor actor) {
        return mapper.findOwn(actor.tenantId(),actor.key(),actor.issuer(),actor.subject()).stream().findFirst()
            .orElseThrow(() -> new TradeException("NOT_FOUND", "会员不存在"));
    }
    /** 展示名称更新与冻结共用会员行锁和版本，避免并发操作互相覆盖。 */
    public String updateProfile(Actor actor,String key,long expectedVersion,String displayName) {
        Checks.text(displayName,100); requireVersion(expectedVersion);
        return commands.run(actor,"member.profile",key,Checks.hash(expectedVersion,displayName),()->{
            LockedMember current=lockOwn(actor);
            checkVersion(current,expectedVersion);
            if(!current.status().equals("ACTIVE")) throw new TradeException("MEMBER_FROZEN","冻结会员不能修改资料");
            mapper.updateDisplayName(displayName,actor.tenantId(),current.id());
            audit.append(actor,"member.profile","MEMBER",current.id(),"会员修改展示名称",current.version(),current.version()+1);
            outbox.append(actor.tenantId(),"member.profile-updated.internal.v0",current.id());
            return current.id();
        });
    }
    /** 后台只能对本租户会员执行显式状态转换，冻结不会删除消费事实或撤回权利。 */
    public String changeStatus(Actor operator,String key,long memberId,long expectedVersion,String target,String reason) {
        Checks.positive(memberId); requireVersion(expectedVersion); Checks.text(reason,255);
        if(!"ACTIVE".equals(target) && !"FROZEN".equals(target)) throw new TradeException("INVALID_ARGUMENT","会员目标状态不合法");
        return commands.run(operator,"member.status",key,Checks.hash(memberId,expectedVersion,target,reason),()->{
            var rows=mapper.lockById(operator.tenantId(),memberId);
            LockedMember current=rows.stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","会员不存在"));
            checkVersion(current,expectedVersion);
            if(current.status().equals(target)) throw new TradeException("STATE_CONFLICT","会员已经处于目标状态");
            mapper.updateStatus(target,operator.tenantId(),memberId);
            audit.append(operator,"member.status."+target,"MEMBER",current.id(),reason,current.version(),current.version()+1);
            outbox.append(operator.tenantId(),"member.status-changed.internal.v0",current.id());
            return current.id();
        });
    }
    /** 管理列表仅输出业务资料，不输出身份签发方、登录subject或密钥。 */
    public List<Member> list(long tenant,long after,int limit) {
        pagination(tenant,after,limit);
        return mapper.listMembers(tenant,after,limit);
    }
    /** 同意必须引用已发布版本；撤回针对该用途的最近一次同意，旧版本不能撤回新授权。 */
    public String consent(Actor actor,String key,long expectedVersion,String purpose,String documentVersion,String action) {
        requireVersion(expectedVersion); Checks.text(purpose,64); Checks.text(documentVersion,64);
        if(!"GRANT".equals(action) && !"WITHDRAW".equals(action)) throw new TradeException("INVALID_ARGUMENT","同意动作不合法");
        return commands.run(actor,"member.consent",key,Checks.hash(expectedVersion,purpose,documentVersion,action),()->{
            LockedMember member=lockOwn(actor);
            checkVersion(member,expectedVersion);
            // 冻结限制新增业务授权，但不能阻止会员撤回已给出的同意。
            if(action.equals("GRANT") && !member.status().equals("ACTIVE")) throw new TradeException("MEMBER_FROZEN","冻结会员不能新增同意");
            var documents=mapper.findConsentDocumentIds(actor.tenantId(),purpose,documentVersion);
            if(documents.isEmpty()) throw new TradeException("NOT_FOUND","条款版本未发布");
            var previous=mapper.lockLatestConsent(actor.tenantId(),member.id(),purpose);
            if(action.equals("WITHDRAW") && (previous.isEmpty() || !previous.getFirst().action().equals("GRANT")
                || !previous.getFirst().documentVersion().equals(documentVersion)))
                throw new TradeException("STATE_CONFLICT","没有该版本可撤回的当前同意");
            if(action.equals("GRANT") && !previous.isEmpty() && previous.getFirst().action().equals("GRANT")
                && previous.getFirst().documentVersion().equals(documentVersion))
                throw new TradeException("STATE_CONFLICT","该版本已处于同意状态");
            mapper.insertConsent(actor.tenantId(),member.id(),documents.getFirst(),purpose,documentVersion,action,member.version()+1,key);
            mapper.advanceMemberVersion(actor.tenantId(),member.id());
            String consentId=mapper.findConsentId(actor.tenantId(),member.id(),key);
            audit.append(actor,"member.consent."+action,"MEMBER",member.id(),"会员对条款用途执行同意动作",member.version(),member.version()+1);
            outbox.append(actor.tenantId(),"member.consent-changed.internal.v0",consentId);
            return consentId;
        });
    }
    /** 返回本人追加型同意历史，查询不会改变授权状态；按事件ID正序分页。 */
    public List<Consent> consents(Actor actor,long after,int limit) {
        pagination(actor.tenantId(),after,limit);
        String memberId=me(actor).memberId();
        return mapper.listConsents(actor.tenantId(),memberId,after,limit);
    }
    /** 交易命令持有会员行锁，冻结与创建报价／订单之间有明确先后顺序。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public long requireActive(Actor actor) {
        LockedMember member=lockOwn(actor);
        if(!member.status().equals("ACTIVE")) throw new TradeException("MEMBER_FROZEN","冻结会员不能发起新交易");
        return Long.parseLong(member.id());
    }
    /** 冻结不妨碍读取本人已有报价，避免状态限制隐藏历史交易事实。 */
    @Override
    public long memberId(Actor actor) { return Long.parseLong(me(actor).memberId()); }
    private LockedMember lockOwn(Actor actor) {
        return mapper.lockOwn(actor.tenantId(),actor.key(),actor.issuer(),actor.subject()).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","会员不存在"));
    }
    private void requireVersion(long version) {
        if(version<0) throw new TradeException("INVALID_ARGUMENT","版本不能为负数");
    }
    private void checkVersion(LockedMember current,long expected) {
        if(current.version()!=expected) throw new TradeException("VERSION_CONFLICT","会员已变化，请重新读取版本");
    }
    private void pagination(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
    }




}
