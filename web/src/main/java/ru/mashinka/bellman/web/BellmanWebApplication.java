package ru.mashinka.bellman.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа веб-приложения.
 *
 * <p>Сервер слушает все сетевые интерфейсы, поэтому калькулятор открывается
 * и с самого компьютера, и с телефона в той же сети Wi-Fi.
 */
@SpringBootApplication
public class BellmanWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BellmanWebApplication.class, args);
    }
}
