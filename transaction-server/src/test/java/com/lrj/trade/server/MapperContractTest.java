package com.lrj.trade.server;

import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;

/** Mapper与XML双向核对，防止漏映射、参数拼写错误和动态SQL注入回到工程。 */
class MapperContractTest {
    @Test void xmlStatementsAndTypedMethodsMatchExactly() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        var builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
        Set<String> namespaces=new HashSet<>();
        int statements=0;
        try(var walk=sourceFiles(root,"src/main/resources/mapper").stream()) {
            for(Path file:walk.filter(p->p.toString().contains("/src/main/resources/mapper/") && p.toString().endsWith(".xml")).toList()) {
                String xml=Files.readString(file);
                assertFalse(xml.contains("${"),"禁止文本替换："+file);
                Element mapper=builder.parse(file.toFile()).getDocumentElement();
                String namespace=mapper.getAttribute("namespace");
                assertTrue(namespaces.add(namespace),"重复命名空间："+namespace);
                Class<?> type=Class.forName(namespace);
                assertTrue(type.isInterface() && type.isAnnotationPresent(Mapper.class));
                Map<String,Element> sql=new HashMap<>();
                for(int i=0;i<mapper.getChildNodes().getLength();i++) {
                    if(!(mapper.getChildNodes().item(i) instanceof Element element)) continue;
                    assertNotEquals("cache",element.getTagName());
                    assertNotEquals("cache-ref",element.getTagName());
                    if(!Set.of("select","insert","update","delete").contains(element.getTagName())) continue;
                    assertNull(sql.put(element.getAttribute("id"),element));
                    if(element.getTagName().equals("select")) assertEquals("false",element.getAttribute("useCache"));
                }
                assertEquals(type.getDeclaredMethods().length,sql.size(),namespace);
                for(var method:type.getDeclaredMethods()) {
                    Element statement=sql.get(method.getName());
                    assertNotNull(statement,namespace+"."+method.getName());
                    Set<String> parameters=new HashSet<>();
                    for(var parameter:method.getParameters()) {
                        assertNotNull(parameter.getAnnotation(Param.class),method.toString());
                        assertTrue(parameters.add(parameter.getAnnotation(Param.class).value()));
                        assertNotEquals(Object.class,parameter.getType(),"不允许无类型参数");
                        assertFalse(Map.class.isAssignableFrom(parameter.getType()),"不使用通用参数Map替代明确合同");
                    }
                    Set<String> bindings=new HashSet<>();
                    var matcher=Pattern.compile("#\\{([A-Za-z][A-Za-z0-9]*)(?:\\.([A-Za-z][A-Za-z0-9]*))?}").matcher(expandedText(statement,mapper));
                    while(matcher.find()) {
                        String bindingRoot=matcher.group(1);bindings.add(bindingRoot);
                        if(matcher.group(2)!=null) {
                            var parameter=Arrays.stream(method.getParameters()).filter(p->p.getAnnotation(Param.class).value().equals(bindingRoot)).findFirst().orElseThrow();
                            assertTrue(parameter.getType().isRecord(),"嵌套绑定仅允许明确record合同："+method);
                            assertNotNull(parameter.getType().getMethod(matcher.group(2)),"绑定属性必须是实际record字段");
                        }
                    }
                    // foreach的局部别名不是Mapper参数；集合必须来自明确声明的参数，值仍采用绑定。
                    var loops=statement.getElementsByTagName("foreach");
                    for(int i=0;i<loops.getLength();i++) {
                        var loop=(Element)loops.item(i); String collection=loop.getAttribute("collection");
                        assertTrue(parameters.contains(collection),"集合必须绑定Mapper参数："+method);
                        assertTrue(bindings.remove(loop.getAttribute("item")),"集合元素必须作为绑定值使用："+method);
                        bindings.add(collection);
                    }
                    assertEquals(parameters,bindings,method.toString());
                    boolean clockQuery=namespace.equals("com.lrj.trade.jobs.mapper.AdminJobMapper") && method.getName().equals("now");
                    if(clockQuery) {
                        // 数据库时钟不访问业务表，精确语句断言防止该例外扩展为无租户查询。
                        assertTrue(parameters.isEmpty());assertEquals("select",statement.getTagName());
                        assertEquals("SELECT UTC_TIMESTAMP(3)",statement.getTextContent().strip());
                    }
                    if(!namespace.endsWith("SeedDatabaseMapper") && !clockQuery) {
                        if(namespace.equals("com.lrj.trade.jobs.mapper.JobLeaseMapper") && method.getName().equals("nextTenant")) {
                            // 仅此内部全租户发现方法例外；写入及其他查询继续强制显式租户。
                            assertEquals(Set.of("type","after"),parameters);
                            assertEquals("select",statement.getTagName());
                            assertTrue(statement.getTextContent().contains("LIMIT 1"));
                        } else if(namespace.equals("com.lrj.trade.core.mapper.OutboxMaintenanceMapper") && method.getName().equals("nextTenant")) {
                            // 仅内部维护发现允许跨租户定位，结果至多1个ID，没有HTTP入口。
                            assertEquals(Set.of("after"),parameters);assertEquals("select",statement.getTagName());
                            assertTrue(statement.getTextContent().contains("LIMIT 1"));
                        } else if(Set.of("com.lrj.trade.payment.mapper.PaymentFinancialSnapshotMapper","com.lrj.trade.refund.mapper.RefundFinancialSnapshotMapper","com.lrj.trade.core.mapper.ReconciliationFinancialSnapshotMapper").contains(namespace) && method.getName().equals("snapshot")) {
                            // 固定资金状态聚合不返回业务明细，逐分支验证覆盖索引和扫描上限。
                            assertTrue(parameters.isEmpty());assertEquals("select",statement.getTagName());
                            int branches=namespace.contains("PaymentFinancial")?5:2;
                            assertEquals(branches,statement.getTextContent().split("LIMIT 10001",-1).length-1);
                            assertEquals(branches,statement.getTextContent().split("FORCE INDEX",-1).length-1);
                        } else if(namespace.equals("com.lrj.trade.jobs.mapper.OutboxQueueSnapshotMapper") && method.getName().equals("snapshot")) {
                            // 固定发布类型的内部监控仅返回三行聚合，每分支最多10001个覆盖索引项。
                            assertTrue(parameters.isEmpty());assertEquals("select",statement.getTagName());
                            assertEquals(3,statement.getTextContent().split("LIMIT 10001",-1).length-1);
                            assertEquals(3,statement.getTextContent().split("job_type='OUTBOX_PUBLISH'",-1).length-1);
                        } else assertTrue(parameters.contains("tenant"),"业务SQL必须显式传递租户："+method);
                        if(!Set.of("com.lrj.trade.jobs.mapper.OutboxQueueSnapshotMapper","com.lrj.trade.payment.mapper.PaymentFinancialSnapshotMapper","com.lrj.trade.refund.mapper.RefundFinancialSnapshotMapper","com.lrj.trade.core.mapper.ReconciliationFinancialSnapshotMapper").contains(namespace))
                            assertTrue(statement.getTextContent().contains("tenant_id"),method.toString());
                    }
                    statements++;
                }
            }
        }
        assertTrue(statements>=60,"不得遗漏当前持久化语句");
        try(var walk=sourceFiles(root,"src/main/java").stream()) {
            for(Path file:walk.filter(p->p.toString().contains("/src/main/java/") && p.toString().endsWith("Mapper.java")).toList()) {
                String source=Files.readString(file);
                var matcher=Pattern.compile("package ([\\w.]+);").matcher(source);
                assertTrue(matcher.find());
                String name=matcher.group(1)+"."+file.getFileName().toString().replace(".java","");
                assertTrue(namespaces.contains(name),"接口缺少XML："+name);
            }
        }
    }
    /** 展开静态SQL片段后检查实际绑定，避免漏掉复用投影中的参数。 */
    private String expandedText(Element element,Element mapper) {
        var text=new StringBuilder();
        for(int i=0;i<element.getChildNodes().getLength();i++) {
            var child=element.getChildNodes().item(i);
            if(child instanceof Element nested) {
                if(nested.getTagName().equals("include")) {
                    String ref=nested.getAttribute("refid");Element found=null;
                    for(int j=0;j<mapper.getChildNodes().getLength();j++)
                        if(mapper.getChildNodes().item(j) instanceof Element fragment && fragment.getTagName().equals("sql") && fragment.getAttribute("id").equals(ref)) found=fragment;
                    assertNotNull(found,"SQL引用必须可解析："+ref);text.append(expandedText(found,mapper));
                } else text.append(expandedText(nested,mapper));
            } else text.append(child.getTextContent());
        }
        return text.toString();
    }
    // 与源码门禁一样限定目录，避免碰到Surefire正在替换的报告或其他构建产物。
    private List<Path> sourceFiles(Path root,String suffix) throws Exception {
        List<Path> result=new ArrayList<>();
        try(var modules=Files.list(root)) {
            for(Path module:modules.filter(p->p.getFileName().toString().startsWith("transaction-")).toList()) {
                Path source=module.resolve(suffix);
                if(!Files.isDirectory(source)) continue;
                try(var files=Files.walk(source)) { result.addAll(files.filter(Files::isRegularFile).toList()); }
            }
        }
        return result;
    }

}
