package com.lrj.trade.member;

import com.lrj.trade.core.*;
import java.net.URI;
import java.util.List;
import com.lrj.trade.member.mapper.ConsentDocumentMapper;
import com.lrj.trade.member.model.*;
import org.springframework.stereotype.Service;

/** 条款版本注册表；只接受后台发布的版本，防止客户端编造未展示的条款证据。 */
@Service
public class ConsentDocumentService {
    private final ConsentDocumentMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    /** 使用业务幂等和审计，已发布的版本不提供覆盖接口。 */
    public ConsentDocumentService(ConsentDocumentMapper mapper,Commands commands,AuditWriter audit) {
        this.mapper=mapper; this.commands=commands; this.audit=audit;
    }
    /** 发布操作不抓取URL；条款内容准确性及摘要由受权运营发布流程负责。 */
    public String publish(Actor actor,String key,String purpose,String version,String title,String contentUrl,String contentHash) {
        Checks.text(purpose,64); Checks.text(version,64); Checks.text(title,200); Checks.text(contentUrl,1000);
        if(contentHash==null || !contentHash.matches("[0-9a-f]{64}")) throw new TradeException("INVALID_ARGUMENT","条款摘要必须为小写SHA256");
        try {
            URI uri=URI.create(contentUrl);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid) { throw new TradeException("INVALID_ARGUMENT","条款内容必须是无内嵌凭据的HTTPS地址"); }
        return commands.run(actor,"consent-document.publish",key,Checks.hash(purpose,version,title,contentUrl,contentHash),()->{
            mapper.insertDocument(actor.tenantId(),purpose,version,title,contentUrl,contentHash);
            String id=mapper.findDocumentId(actor.tenantId(),purpose,version);
            audit.append(actor,"consent-document.publish","CONSENT_DOCUMENT",id,"发布不可变条款版本",0,0);
            return id;
        });
    }
    /** 展示已发布条款的有界列表，不将不同租户的同名条款混合。 */
    public List<Document> list(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listDocuments(tenant,after,limit);
    }

}
