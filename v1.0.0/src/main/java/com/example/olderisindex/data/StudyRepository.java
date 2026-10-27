package com.example.olderisindex.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Repository
public class StudyRepository {
    private static final String WHERE_BY_AN   = "st.accession_no = :an";
    private static final String WHERE_BY_SUID = "st.study_iuid   = :suid";

    private final NamedParameterJdbcTemplate jdbc;
    private final String sqlTemplate;

    public StudyRepository(NamedParameterJdbcTemplate jdbc,
                           @Value("classpath:sql/find-studies.sql") Resource sqlResource) {
        this.jdbc = jdbc;
        try (var in = sqlResource.getInputStream()) {
            this.sqlTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить find-studies.sql", e);
        }
    }

    public List<StudyRow> findByAn(String an) {
        return jdbc.query(sqlTemplate.replace("{{WHERE}}", WHERE_BY_AN),
                new MapSqlParameterSource("an", an), MAPPER);
    }
    public List<StudyRow> findBySuid(String suid) {
        return jdbc.query(sqlTemplate.replace("{{WHERE}}", WHERE_BY_SUID),
                new MapSqlParameterSource("suid", suid), MAPPER);
    }
    private static final RowMapper<StudyRow> MAPPER = (rs, i) -> new StudyRow(
            rs.getString("study_iuid"), rs.getString("series_iuid"),
            rs.getString("modality"), rs.getString("file_name"),
            rs.getString("storage_group_id"), rs.getString("storage_id"),
            rs.getString("storage_path"));
}
