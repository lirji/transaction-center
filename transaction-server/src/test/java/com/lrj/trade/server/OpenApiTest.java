package com.lrj.trade.server;

import com.lrj.trade.server.operations.OperationsReportController;
import com.lrj.trade.server.auditarchive.RefundArchiveController;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** 校验已交接API与控制器映射一致，避免把设计中的未来接口列成已实现。 */
class OpenApiTest {
    @Test void everyControllerEndpointHasExactlyOneDocumentedOperation() throws Exception {
        var spec=JsonMapper.builder().build().readTree(Files.readString(Path.of("../docs/openapi.json")));
        var paths=spec.path("paths");
        Set<String> actual=new HashSet<>();
        for(var controller:List.of(AdminQueryController.class,NumberedListsController.class,NumberedOperationalListsController.class,ConsoleAssetsController.class,ConsoleFilesController.class,TradeController.class,EvidenceController.class,OutboxOperationsController.class,ReconciliationController.class,ReconciliationCaseController.class,ReconciliationOperationController.class,AdmissionController.class,FulfillmentController.class,OperationsReportController.class,RefundArchiveController.class)) for(var method:controller.getDeclaredMethods()) {
            String verb=null; String route=null;
            if(method.isAnnotationPresent(GetMapping.class)) { verb="get"; route=method.getAnnotation(GetMapping.class).value()[0]; }
            if(method.isAnnotationPresent(PostMapping.class)) { verb="post"; route=method.getAnnotation(PostMapping.class).value()[0]; }
            if(method.isAnnotationPresent(PatchMapping.class)) { verb="patch"; route=method.getAnnotation(PatchMapping.class).value()[0]; }
            if(verb==null) continue;
            String full=controller.getAnnotation(RequestMapping.class).value()[0]+route;
            actual.add(verb+" "+full);
            assertTrue(paths.path(full).has(verb),"缺少接口合同："+full);
        }
        Set<String> documented=new HashSet<>();
        for(var path:paths.properties()) for(var operation:path.getValue().properties())
            documented.add(operation.getKey()+" "+path.getKey());
        assertEquals(actual,documented);
        assertEquals(104,actual.size());
    }
}
