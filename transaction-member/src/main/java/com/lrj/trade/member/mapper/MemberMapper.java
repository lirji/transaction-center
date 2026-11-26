package com.lrj.trade.member.mapper;

import com.lrj.trade.core.query.AdminQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.member.model.*;

/** MemberService的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface MemberMapper {
    /** 新增租户内会员，由唯一约束阻止重复身份。 */
    int insertMember(@Param("tenant") long tenant, @Param("identityHash") String identityHash, @Param("issuer") String issuer, @Param("subject") String subject, @Param("displayName") String displayName,@Param("registeredAt") java.time.Instant registeredAt);

    /** 读取刚创建的会员编号，不跨租户解析身份。 */
    Long findIdByIdentity(@Param("tenant") long tenant, @Param("identityHash") String identityHash);

    /** 建立消费事实锁定行，注册事务失败时一起回滚。 */
    int insertTradeFact(@Param("tenant") long tenant, @Param("memberId") long memberId);

    /** 精确匹配可信身份，返回本人展示资料及消费事实。 */
    List<Member> findOwn(@Param("tenant") long tenant, @Param("identityHash") String identityHash, @Param("issuer") String issuer, @Param("subject") String subject);

    /** 在调用方已持有会员行锁后更新资料及版本。 */
    int updateDisplayName(@Param("displayName") String displayName, @Param("tenant") long tenant, @Param("memberId") String memberId);

    /** 按租户锁定会员，串行化后台状态和会员命令。 */
    List<LockedMember> lockById(@Param("tenant") long tenant, @Param("memberId") long memberId);

    /** 在会员行锁内更新状态，不修改既有消费事实。 */
    int updateStatus(@Param("status") String status, @Param("tenant") long tenant, @Param("memberId") long memberId);

    /** 按主键有界分页，不向管理端暴露登录身份。 */
    List<Member> listMembers(@Param("tenant") long tenant, @Param("after") long after, @Param("limit") int limit);

    /** 查找不可变条款版本，未发布版本返回空列表。 */
    List<Long> findConsentDocumentIds(@Param("tenant") long tenant, @Param("purpose") String purpose, @Param("documentVersion") String documentVersion);

    /** 当前读最近授权，避免事务快照导致撤回旧授权。 */
    List<ConsentState> lockLatestConsent(@Param("tenant") long tenant, @Param("memberId") String memberId, @Param("purpose") String purpose);

    /** 追加授权历史，不能覆盖先前事件。 */
    int insertConsent(@Param("tenant") long tenant, @Param("memberId") String memberId, @Param("documentId") long documentId, @Param("purpose") String purpose, @Param("documentVersion") String documentVersion, @Param("action") String action, @Param("memberVersion") long memberVersion, @Param("requestKey") String requestKey);

    /** 授权变更与资料共用会员版本，调用方必须已持有行锁。 */
    int advanceMemberVersion(@Param("tenant") long tenant, @Param("memberId") String memberId);

    /** 在同一事务中读取本次授权事件编号。 */
    String findConsentId(@Param("tenant") long tenant, @Param("memberId") String memberId, @Param("requestKey") String requestKey);

    /** 按事件编号分页读取本人授权历史。 */
    List<Consent> listConsents(@Param("tenant") long tenant, @Param("memberId") String memberId, @Param("after") long after, @Param("limit") int limit);

    /** 锁定可信身份所属会员，避免冻结与新交易并发穿透。 */
    List<LockedMember> lockOwn(@Param("tenant") long tenant, @Param("identityHash") String identityHash, @Param("issuer") String issuer, @Param("subject") String subject);
    /** 管理查询在本租户的完整数据范围执行筛选，先过滤再按主键分页。 */
    List<Member> listAdminMembers(@Param("tenant") long tenant,@Param("q") AdminQuery query);

}
