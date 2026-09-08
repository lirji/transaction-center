package com.lrj.trade.server;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 以JDK语法树检查真实声明的中文注释，避免仅用正则猜测Java方法。 */
class QualityGateTest {
    @Test void productionClassesAndPublicMethodsHaveChineseDocumentation() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        List<Path> sources;
        sources=productionSources(root);
        assertFalse(sources.isEmpty());
        var compiler=ToolProvider.getSystemJavaCompiler();
        List<String> missing=new ArrayList<>();
        try(var manager=compiler.getStandardFileManager(null,null,null)) {
            JavacTask task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjectsFromPaths(sources));
            var docs=DocTrees.instance(task);
            var units=task.parse();
            for(var unit:units) new TreePathScanner<Void,Void>() {
                @Override public Void visitClass(ClassTree tree,Void unused) {
                    if(!tree.getSimpleName().isEmpty()) check(tree.getSimpleName().toString());
                    return super.visitClass(tree,unused);
                }
                @Override public Void visitMethod(MethodTree tree,Void unused) {
                    boolean interfaceMember=getCurrentPath().getParentPath().getLeaf().getKind()==Tree.Kind.INTERFACE;
                    if(tree.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC) || interfaceMember) check(tree.getName().toString());
                    return super.visitMethod(tree,unused);
                }
                private void check(String name) {
                    // DocTree.toString会转义中文，读取原始注释文本才能验证汉字。
                    String comment=docs.getDocComment(getCurrentPath());
                    if(comment==null || !comment.matches("(?s).*[\\p{IsHan}].*")) missing.add(unit.getSourceFile().getName()+":"+name);
                }
            }.scan(unit,null);
        }
        assertEquals(List.of(),missing,"生产类／公共方法缺少中文说明");
    }

    @Test void domainModulesDoNotImportControllersOrOtherModuleImplementations() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        for(String module:List.of("core","member","catalog","inventory","order","payment","refund","jobs")) {
            try(var walk=Files.walk(root.resolve("transaction-"+module+"/src/main/java"))) {
                for(Path file:walk.filter(p->p.toString().endsWith(".java")).toList()) {
                    String source=Files.readString(file);
                    assertFalse(source.contains("com.lrj.trade.server"),file.toString());
                    for(String other:List.of("member","catalog","inventory","order","payment","refund","jobs")) {
                        if(other.equals(module)) continue;
                        String checked=source.replace("import com.lrj.trade.catalog.CatalogPort;","")
                            .replace("import com.lrj.trade.member.MemberTradingPort;","")
                            .replace("import com.lrj.trade.member.MemberTradeFactPort;","")
                            .replace("import com.lrj.trade.order.OrderPaymentPort;","")
                            .replace("import com.lrj.trade.order.OrderRefundPort;","")
                            .replace("import com.lrj.trade.payment.PaymentQueryPlanPort;","")
                            .replace("import com.lrj.trade.payment.PaymentRefundPort;","")
                            .replace("import com.lrj.trade.payment.PaymentChannelBindingPort;","")
                            .replace("import com.lrj.trade.order.MerchantTradingPort;","")
                            .replace("import com.lrj.trade.payment.PaymentCallbackPort;","")
                            .replace("import com.lrj.trade.refund.RefundCallbackPort;","")
                            .replace("import com.lrj.trade.refund.RefundRecoveryPort;","")
                            .replace("import com.lrj.trade.refund.RefundCompensationPort;","")
                            .replace("import com.lrj.trade.refund.RefundChannelPort;","")
                            .replace("import com.lrj.trade.payment.PaymentRecoveryPort;","")
                            .replace("import com.lrj.trade.payment.OrderResolutionPort;","")
                            .replace("import com.lrj.trade.payment.PaymentChannelPort;","")
                            .replace("import com.lrj.trade.payment.PaymentRiskPort;","")
                            .replace("import com.lrj.trade.catalog.QuotePort;","")
                            .replace("import com.lrj.trade.inventory.InventoryReturnPort;","")
                            .replace("import com.lrj.trade.inventory.InventoryTradingPort;","");
                        assertFalse(checked.contains("com.lrj.trade."+other+"."),file.toString());
                    }
                }
            }
        }
    }

    @Test void productionSqlAndPersistenceDependenciesStayBehindMappers() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        try(var walk=productionSources(root).stream()) {
            for(Path file:walk.filter(p->p.toString().contains("/src/main/java/") && p.toString().endsWith(".java")).toList()) {
                String source=Files.readString(file);
                assertFalse(source.contains("JdbcTemplate") || source.contains("JdbcClient"),file.toString());
                assertFalse(source.matches("(?s).*\\b(SELECT|INSERT\\s+INTO|UPDATE\\s+tc_|DELETE\\s+FROM)\\b.*"),
                    "生产Java禁止内嵌SQL："+file);
                if(!file.toString().contains("/mapper/")) {
                    assertFalse(source.contains("org.apache.ibatis.session") || source.contains("java.sql.")
                        || source.contains("org.springframework.jdbc"),"业务层不能绕过Mapper："+file);
                }
                if(file.toString().contains("/mapper/") || file.toString().contains("/model/")) {
                    // 只读模型和持久化代码不依赖Service，避免双向耦合。
                    assertFalse(source.matches("(?s).*import\\s+com\\.lrj\\.trade\\.[^;]*Service;.*"),file.toString());
                }
                assertFalse(source.matches("(?s).*@(Select|Insert|Update|Delete|SelectProvider|InsertProvider|UpdateProvider|DeleteProvider)\\s*\\(.*"),
                    "SQL与Provider必须由XML替代："+file);
            }
        }
    }
    // 只走实际源码树；不能先遍历整个仓库再过滤，否则报告文件的原子替换会造成偶发IO失败。
    private List<Path> productionSources(Path root) throws Exception {
        List<Path> result=new ArrayList<>();
        try(var modules=Files.list(root)) {
            for(Path module:modules.filter(p->p.getFileName().toString().startsWith("transaction-")).toList()) {
                Path source=module.resolve("src/main/java");
                if(!Files.isDirectory(source)) continue;
                try(var files=Files.walk(source)) { result.addAll(files.filter(p->p.toString().endsWith(".java")).toList()); }
            }
        }
        return result;
    }

}
