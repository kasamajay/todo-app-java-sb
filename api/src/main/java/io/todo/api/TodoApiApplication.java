package io.todo.api;

import io.todo.api.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * The todo-app-java-sb API server: a Spring Boot app exposing exactly the
 * same HTTP contract as todo-app's Go API (api/cmd/api/main.go), so the
 * unchanged React frontend works against any of the three backends.
 */
@SpringBootApplication
public class TodoApiApplication {

    private final AppConfig config;
    private final AppState state;

    public TodoApiApplication(AppConfig config, AppState state) {
        this.config = config;
        this.state = state;
    }

    public static void main(String[] args) {
        SpringApplication.run(TodoApiApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        AppState.LOG.info("todo-app api listening on {} (data dir: {})", config.addr(), state.dataDir());
    }
}
