package com.example.olderisindex.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ClusterRegistry {

    public record ClusterContext(
            String name,
            String dialect,
            HikariDataSource dataSource,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc
    ) {}

    private final Map<String, ClusterContext> clusters;

    public ClusterRegistry(AppProperties props) {
        if (props.clusters() == null || props.clusters().isEmpty()) {
            throw new IllegalStateException("app.clusters не задан — нечего опрашивать");
        }
        LinkedHashMap<String, ClusterContext> map = new LinkedHashMap<>();
        for (AppProperties.Cluster c : props.clusters()) {
            if (c.name() == null || c.name().isBlank()) {
                throw new IllegalStateException("У кластера пустое имя (app.clusters[].name)");
            }
            if (map.containsKey(c.name())) {
                throw new IllegalStateException("Дубликат имени кластера: " + c.name());
            }
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(c.jdbcUrl());
            ds.setUsername(c.username());
            ds.setPassword(c.password());
            ds.setDriverClassName(c.driverClassName());
            ds.setMaximumPoolSize(c.maxPoolSize() > 0 ? c.maxPoolSize() : 10);
            ds.setMinimumIdle(2);
            ds.setPoolName("olderis-" + c.name());
            ds.setConnectionTimeout(5000);
            ds.setValidationTimeout(3000);

            map.put(c.name(), new ClusterContext(
                    c.name(), c.dialect(), ds,
                    new JdbcTemplate(ds),
                    new NamedParameterJdbcTemplate(ds)));
        }
        this.clusters = Collections.unmodifiableMap(map);
    }

    public Collection<ClusterContext> all()  { return clusters.values(); }
    public List<String> names()              { return List.copyOf(clusters.keySet()); }
    public ClusterContext get(String name)   { return clusters.get(name); }

    @PreDestroy
    public void shutdown() {
        for (ClusterContext c : clusters.values()) {
            try { c.dataSource().close(); } catch (Exception ignored) { }
        }
    }
}
