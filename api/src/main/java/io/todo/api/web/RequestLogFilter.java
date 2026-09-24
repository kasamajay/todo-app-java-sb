package io.todo.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Logs "METHOD path status duration" for every request, like Go's Logging middleware. */
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("todo-app");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            LOG.info("{} {} {} {}", req.getMethod(), req.getRequestURI(), res.getStatus(), duration(System.nanoTime() - start));
        }
    }

    /** Roughly Go's time.Duration.String() for request timings. */
    static String duration(long nanos) {
        if (nanos >= 1_000_000_000L) {
            return String.format("%.6gs", nanos / 1e9);
        }
        if (nanos >= 1_000_000L) {
            return String.format("%.6gms", nanos / 1e6);
        }
        return String.format("%.6gµs", nanos / 1e3);
    }
}
