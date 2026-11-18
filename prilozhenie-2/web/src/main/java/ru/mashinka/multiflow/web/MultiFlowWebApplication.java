package ru.mashinka.multiflow.web;

import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.mashinka.multiflow.core.log.CoreLog;

// сайт «Многополюсный поток»: тот же экран ui/, что в Android-приложении, и тот же расчёт из core.
// Запуск: java -jar multiflow-web.jar (порт 8091, уровень логов - переменная LOG_LEVEL)
@SpringBootApplication
public class MultiFlowWebApplication {

    private static final Logger log = LoggerFactory.getLogger(MultiFlowWebApplication.class);

    public static void main(String[] args) {
        // ядро пишет через java.util.logging; Spring Boot перенаправляет его в общий журнал,
        // а уровень ядра задаём той же переменной LOG_LEVEL, что и у сайта
        Level coreLevel = CoreLog.parseLevel(System.getenv("LOG_LEVEL"));
        CoreLog.setLevel(coreLevel);

        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                log.error("uncaught exception in thread {}", thread.getName(), error));

        log.info("starting multiflow web: java {}, LOG_LEVEL={}, core log level {}",
                System.getProperty("java.version"), System.getenv().getOrDefault("LOG_LEVEL", "(not set, info)"),
                coreLevel.getName());
        try {
            SpringApplication.run(MultiFlowWebApplication.class, args);
        } catch (RuntimeException startupFailure) {
            log.error("multiflow web failed to start", startupFailure);
            throw startupFailure;
        }
    }
}
