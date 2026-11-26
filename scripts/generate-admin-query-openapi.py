#!/usr/bin/env python3
"""同步管理读取合同：从安全投影record提取字段，路由与枚举独立校验。"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / 'docs/openapi.json'
spec = json.loads(p.read_text())
schemas = spec['components']['schemas']
nullable = {
    'AdminOrderView': {'warehouseId','paidAt','completedAt','paymentStartedAt','cancelledAt'},
    'AdminReturnView': {'receiptNo'},
    'AdminPaymentView': {'activeAttemptNo','succeededReceiptNo','closeFenceAt'},
    'AdminAttemptView': {'submittedAt','closedAt'},
    'AdminRefundView': {'approvalRef','approvalDeadline'},
    'AdminJobView': {'leaseUntil','lastError'},
    'AdminRefundItem': {'orderItemId'},
    'AdminFulfillmentView': {'completedAt'},
}


def ref(name): return {'$ref': '#/components/schemas/' + name}
def obj(properties): return {'type':'object','properties':properties,'required':list(properties),'additionalProperties':False}
def nullref(name): return {'anyOf':[ref(name),{'type':'null'}]}
def array(name): return {'type':'array','items':ref(name)}
def instant(): return {'type':'string','format':'date-time'}

for path in ROOT.glob('transaction-*/src/main/java/com/lrj/trade/*/model/Admin*.java'):
    text=path.read_text();match=re.search(r'public record (\w+)\((.*?)\)\s*(?:implements[^\{]*)?\{',text,re.S)
    if not match: continue
    name,body=match.groups();props={}
    # 商品搜索使用独立目录契约，保留兼容 Product 的约束与可空修订。
    if name=='AdminProduct': continue
    for typ,key in re.findall(r'(Map<String,String>|String|long|Instant|boolean)\s+(\w+)',body):
        if key=='cursor': continue
        field={'type':{'String':'string','long':'integer','Instant':'string','boolean':'boolean','Map<String,String>':'object'}[typ]}
        if typ=='Instant':field['format']='date-time'
        if typ=='long':field.update(minimum=0,maximum=9007199254740991)
        if typ.startswith('Map'):field['additionalProperties']={'type':'string'}
        if key in nullable.get(name,set()):field['type']=[field['type'],'null']
        props[key]=field
    schemas[name]=obj(props)

states={
 'Order':{'status':['PENDING_PAYMENT','CLOSING','CANCELLED','PAID','FULFILLING','COMPLETED'],'financialStatus':['UNPAID','NO_PAYMENT_REQUIRED','PAID','PARTIALLY_REFUNDED','REFUNDED']},
 'Return':{'status':['OPEN','INSPECTED']},'Payment':{'status':['READY','PENDING','SUCCEEDED','CLOSED','REVIEW']},
 'Attempt':{'status':['PREPARED','UNKNOWN','SUCCEEDED','CLOSED']},
 'Receipt':{'disposition':['NORMAL','EXCESS','AMOUNT_MISMATCH','AFTER_CLOSE','AFTER_TERMINAL']},
 'Refund':{'status':['PENDING_APPROVAL','READY','SUBMITTED','SUCCEEDED','FAILED','REJECTED','EXPIRED']},
 'Job':{'status':['READY','RUNNING','DONE','REVIEW'],'jobType':['PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE','REFUND_CALLBACK','REFUND_RECOVER','REFUND_COMPENSATE','OUTBOX_PUBLISH','RECON_QUERY']},'Quote':{}}
for name,enums in states.items():
    for field,values in enums.items():schemas['Admin'+name+'View']['properties'][field]['enum']=values
    schemas['Admin'+name+'Page']=obj({'items':array('Admin'+name+'View'),'nextAfter':{'type':['string','null'],'pattern':'^[1-9][0-9]{0,18}$'},'hasMore':{'type':'boolean'},'observedAt':instant()})
    props=dict(schemas['Admin'+name+'View']['properties']);props['observedAt']=instant()
    if name=='Order':props.update(items=array('AdminOrderItem'),fulfillment=nullref('AdminFulfillmentView'))
    if name=='Return':props['inspection']=nullref('AdminInspectionView')
    if name=='Quote':props['items']=array('QuoteItem')
    if name=='Refund':props.update(items=array('AdminRefundItem'),processingReasonCode={'type':['string','null'],'description':'持久REFUND_RECOVER任务的结构化错误分类；无可靠来源时null，不等于渠道最终失败原因。'})
    if name=='Job':props['businessReference']=nullref('AdminBusinessReference')
    schemas['Admin'+name+'Detail']=obj(props)

def param(name,typ=None,enum=None,default=None):
    s={'type':typ or 'string'}
    if name.endswith('Id'):s['pattern']='^[1-9][0-9]{0,18}$'
    if name.startswith(('created','confirmed','approvalDeadline')):s.update(format='date-time',description='两端同时提供，UTC毫秒精度，[from,to)，最大90天。')
    if name.startswith('confirmed'):s['description']='交易中心确认实收时间。'+s['description']+' 与创建时间条件取交集。'
    if name.startswith('approvalDeadline'):s['description']='审批截止时间。'+s['description']+' 与创建时间条件取交集。'
    if name=='currency':s['pattern']='^[A-Z]{3}$'
    if name=='after':s.update(pattern='^(0|[1-9][0-9]{0,18})$',default='0')
    if name=='limit':s.update(type='integer',minimum=1,maximum=100,default=20)
    if enum:s['enum']=enum
    if default is not None:s['default']=default
    return {'in':'query','name':name,'schema':s}

base='/api/v1/trade/admin'
def operation(path,method,name,response,parameters,scope=None,body=None,legacy=False):
    required=['trade.admin']+([scope] if scope else [])
    d={'operationId':('adminQuery'+name),'summary':name,'tags':['Admin queries'],
       'description':'当前JWT租户只读查询。需要 '+ ' + '.join(required)+'。未知或重复查询参数拒绝；查询无渠道或资金副作用。新列表主键升序游标，不保证跨页快照；单实例并发及租户配额保护，响应禁止缓存。'+(' 保持原队列数组与默认PENDING_APPROVAL。' if legacy else ''),
       'security':[{'BearerJWT':[]}],'x-required-scopes':required,'parameters':parameters,
       'responses':{'200':{'description':'安全只读投影','headers':{'Cache-Control':{'schema':{'type':'string','const':'no-store'}}},'content':{'application/json':{'schema':response}}}}}
    for code,desc in [('400','查询参数或请求体无效'),('401','未认证或令牌无效'),('403','缺少操作权限'),('404','资源不存在或属于其他租户'),('429','ADMIN_QUERY_BUSY，Retry-After为1秒'),('503','查询不可用或证据入口关闭/事实不一致')]:
        d['responses'][code]={'description':desc}
        if code not in ('401','403'): d['responses'][code]['content']={'application/json':{'schema':ref('Failure')}}
    if path=='/refunds' and method=='get':d['operationId']='refundQueue'
    if path=='/returns/{returnNo}' and method=='get':d['operationId']='readReturnRequest'
    if body:d['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
    spec['paths'].setdefault(base+path,{})[method]=d
    return d

filters={'Order':'orderNo memberId status financialStatus warehouseId currency','Return':'returnNo orderNo memberId orderItemId warehouseId status','Payment':'paymentNo orderNo memberId status','Receipt':'receiptNo paymentNo orderNo channelCode disposition abnormalOnly','Quote':'quoteId memberId','Refund':'refundNo orderNo memberId status','Job':'jobType status leaseExpired'}
paths={'Order':('orders','orderNo'),'Return':('returns','returnNo'),'Payment':('payments','paymentNo'),'Receipt':('payment-receipts','receiptNo'),'Quote':('quotes','quoteId'),'Refund':('refunds','refundNo'),'Job':('jobs','jobId')}
for name,(route,idname) in paths.items():
    parameters=[param('after'),param('limit'),param('createdFrom'),param('createdTo')]
    for field in filters[name].split():
        choices=states[name].get(field)
        if name=='Refund' and field=='status':choices=['ALL']+choices
        parameters.append(param(field,'boolean' if field in ('abnormalOnly','leaseExpired') else None,choices,'PENDING_APPROVAL' if name=='Refund' and field=='status' else None))
    if name=='Order':
        parameters.append(param('sort',enum=['ID_ASC','CREATED_DESC'],default='ID_ASC'))
        parameters[-1]['description']='CREATED_DESC按创建时间和主键降序，传上一页游标。切换排序时从首页读取。'
    if name=='Refund':
        parameters.extend([param('approvalDeadlineFrom'),param('approvalDeadlineTo')])
    if name=='Receipt':
        parameters.extend([param('confirmedFrom'),param('confirmedTo')])
    scope='trade.admin.jobs.read' if name=='Job' else None
    listing=operation('/'+route,'get',name+'List',array('RefundQueueItem') if name=='Refund' else ref('Admin'+name+'Page'),parameters,scope,legacy=name=='Refund')
    if name=='Order':
        listing['description']=listing['description'].replace('新列表主键升序游标','默认主键升序游标，可按创建时间和主键降序')
    detail=operation('/'+route+'/{'+idname+'}','get',name+'Detail',ref('Admin'+name+'Detail'),[{'in':'path','name':idname,'required':True,'schema':{'type':'string','maxLength':64}}],scope)
    if name=='Return': detail['responses']['200']['headers']['ETag']={'description':'退货申请版本，验收请求使用If-Match。','schema':{'type':'string'}}

operation('/payments/{paymentNo}/attempts','get','PaymentAttempts',ref('AdminAttemptPage'),[{'in':'path','name':'paymentNo','required':True,'schema':{'type':'string','maxLength':64}},param('after'),param('limit'),param('status',enum=states['Attempt']['status'])])
props=dict(schemas['OrderReferralEvidence']['properties']);props['orderNo']={'type':'string'};schemas['AdminOrderEvidence']=obj(props)
operation('/orders/{orderNo}/referral-evidence','get','OrderEvidence',ref('AdminOrderEvidence'),[{'in':'path','name':'orderNo','required':True,'schema':{'type':'string'}}],'trade.admin.evidence.read')
body=obj({'policyVersion':{'type':'string','enum':['REGISTERED_DURING_CAMPAIGN_V1','NEVER_CONFIRMED_PAID_V1']},'campaignStartAt':{'type':['string','null'],'format':'date-time'},'evaluatedAt':{'type':['string','null'],'format':'date-time'}});body['required']=['policyVersion'];body['description']='活动注册策略必须传campaignStartAt；evaluatedAt可省略，禁止未来时间。只读，不预占资格。'
operation('/members/{memberId}/eligibility:query','post','MemberEligibility',ref('CustomerEligibilityResult'),[{'in':'path','name':'memberId','required':True,'schema':{'type':'string','pattern':'^[1-9][0-9]{0,18}$'}}],'trade.admin.evidence.read',body)
fund_kinds=['NORMAL_RECEIPT','ABNORMAL_RECEIPT','NORMAL_REFUND','ABNORMAL_REFUND']
unsigned_minor={'type':'string','pattern':'^(0|[1-9][0-9]*)$'}
schemas['OperationsSummary']=obj({
    'from':instant(),'to':instant(),'observedAt':instant(),'currency':param('currency')['schema'],
    'orderCount':{'type':'integer','minimum':0},'orderPayable':unsigned_minor,
    'paidOrderCount':{'type':'integer','minimum':0},
    'normalReceipt':unsigned_minor,'abnormalReceipt':unsigned_minor,
    'normalRefund':unsigned_minor,'abnormalRefund':unsigned_minor,
    'netReceipt':{'type':'string','pattern':'^(0|-?[1-9][0-9]*)$'},
})
schemas['OperationsFinancialFact']=obj({
    'ledgerNo':{'type':'string'},'orderNo':{'type':'string'},'receiptNo':{'type':'string'},
    'refundNo':{'type':['string','null']},'kind':{'type':'string','enum':fund_kinds},
    'amount':{'type':'integer','minimum':1,'maximum':9007199254740991},
    'currency':param('currency')['schema'],'confirmedAt':instant(),
})
schemas['OperationsFinancialFactPage']=obj({
    'items':array('OperationsFinancialFact'),'nextAfter':{'type':['string','null'],'pattern':'^[1-9][0-9]{0,18}$'},
    'hasMore':{'type':'boolean'},'observedAt':instant(),
})
range_params=[{**param(name),'required':True} for name in ('createdFrom','createdTo','currency')]
summary=operation('/operations/summary','get','OperationsSummary',ref('OperationsSummary'),range_params)
summary['description']='当前 JWT 租户的单币种经营汇总。订单按创建时间，含取消及零元；正常实收和成功退款按各自本地确认时间。过去的正常实收在本期间的成功退款仍计入。汇总金额以最小币种单位的十进制整数字符串返回。需要 trade.admin。'
facts=operation('/operations/financial-facts','get','OperationsFinancialFacts',ref('OperationsFinancialFactPage'),
                range_params+[{**param('scope',enum=fund_kinds+['NET']),'required':True},param('after'),param('limit')])
facts['description']='当前 JWT 租户经营汇总的资金明细。NET 包含正常实收和原收款为正常实收的成功退款，可按账本游标继续读取。不保证跨请求快照。需要 trade.admin。'
p.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n')
