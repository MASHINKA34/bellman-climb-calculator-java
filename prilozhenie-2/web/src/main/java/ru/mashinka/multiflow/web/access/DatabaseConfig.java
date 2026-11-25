package ru.mashinka.multiflow.web.access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// база ключей - один файл SQLite в папке данных. Папку создаём сами и закрываем от других
// пользователей сервера
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    static final String FILE = "keys.db";

    @Bean
    public DataDir dataDir(@Value("${multiflow.data-dir}") String configured) {
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot create the data folder " + dir, failure);
        }
        log.info("data folder {}", dir);
        return new DataDir(dir);
    }

    @Bean
    public DataSource dataSource(DataDir dataDir) {
        Path file = dataDir.path().resolve(FILE);
        long startedAt = System.nanoTime();
        log.info("opening key database {} ({})", file, Files.exists(file) ? "exists" : "will be created");
        HikariConfig config = new HikariConfig();
        config.setPoolName("keys");
        // внешние ключи в SQLite выключены, пока их не включить; WAL - чтение не мешает записи
        config.setJdbcUrl("jdbc:sqlite:" + file + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000");
        // SQLite пишет в одном потоке: одно соединение - и «database is locked» не бывает
        config.setMaximumPoolSize(1);
        HikariDataSource source = new HikariDataSource(config);
        log.info("key database opened in {} ms", (System.nanoTime() - startedAt) / 1_000_000);
        return source;
    }

    // время - отдельным бином: в тестах его двигают вперёд
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // папка данных отдельным типом, чтобы её не перепутать с другими путями
    public record DataDir(Path path) {
    }
}
