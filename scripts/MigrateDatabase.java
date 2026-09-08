import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** 独立执行迁移，避免建表时启动HTTP服务或依赖尚未接通的身份平台。 */
public class MigrateDatabase {
    /** 密码仅从环境读取，不写入参数、源代码或控制台输出。 */
    public static void main(String[] args) {
        String url = System.getenv("TRADE_MIGRATION_URL");
        String user = System.getenv("TRADE_MIGRATION_USER");
        String password = System.getenv("TRADE_MIGRATION_PASSWORD");
        if (args.length != 1 || url == null || user == null || password == null) {
            throw new IllegalArgumentException("需要迁移目录参数及TRADE_MIGRATION_URL/USER/PASSWORD环境变量");
        }
        Flyway flyway = Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:" + Path.of(args[0]).toAbsolutePath())
            .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).load();
        flyway.migrate();
        flyway.validate();
        System.out.println("迁移执行与校验完成");
    }
}
