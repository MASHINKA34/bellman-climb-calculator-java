package ru.mashinka.multiflow.web;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import ru.mashinka.multiflow.core.gomoryhu.GomoryHuSolver;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;
import ru.mashinka.multiflow.web.access.AccessKey;
import ru.mashinka.multiflow.web.access.KeyService;

// после старта: версия, адрес и пробный расчёт встроенного примера.
// Если ядро не считает, это видно в журнале сразу, а не на первом запросе пользователя
@Component
public class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private final Environment environment;
    private final ObjectProvider<BuildProperties> build;
    private final KeyService keys;

    public StartupCheck(Environment environment, ObjectProvider<BuildProperties> build, KeyService keys) {
        this.environment = environment;
        this.build = build;
        this.keys = keys;
    }

    // версия сайта из build-info (pom.xml, site.version); при запуске из IDE её может не быть
    public static String version(ObjectProvider<BuildProperties> build) {
        return Optional.ofNullable(build.getIfAvailable())
                .map(properties -> properties.get("site.version"))
                .orElse("dev");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        log.info("multiflow web {} listening on {}:{}", version(build),
                environment.getProperty("server.address", "0.0.0.0"), environment.getProperty("local.server.port"));

        List<AccessKey> all = keys.list();
        log.info("access keys: {} total, {} active, {} bound to a device", all.size(),
                all.stream().filter(AccessKey::active).count(),
                all.stream().filter(key -> key.device() != null).count());

        long startedAt = System.nanoTime();
        log.info("self-check: solving the built-in example");
        try {
            MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "self-check");
            log.info("self-check ok: {} steps, flow 1-2 = {}, {} ms", solution.getSteps().size(),
                    solution.getFlow(0, 1), (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException failure) {
            // сайт остаётся работать: страница откроется, а ошибку расчёта покажет api/solve
            log.error("self-check failed: the core cannot solve the built-in example", failure);
        }
    }
}
