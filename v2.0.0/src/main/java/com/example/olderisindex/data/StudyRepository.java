package com.example.olderisindex.data;

import com.example.olderisindex.config.ClusterRegistry.ClusterContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Repository
public class StudyRepository {

    private static final String WHERE_BY_AN   = "st.accession_no = :an";
    private static final String WHERE_BY_SUID = "st.study_iuid   = :suid";

    private final String sqlTemplate;

    public StudyRepository(@Value("classpath:sql/find-studies.sql") Resource sqlResource) {
        try (var in = sqlResource.getInputStream()) {
            this.sqlTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить find-studies.sql", e);
        }
    }

    public List<StudyRow> findByAn(ClusterContext ctx, String an) {
        String sql = sqlTemplate.replace("{{WHERE}}", WHERE_BY_AN);
        return ctx.namedJdbc().query(sql, new MapSqlParameterSource("an", an),
                (rs, i) -> map(ctx.name(), rs));
    }

    public List<StudyRow> findBySuid(ClusterContext ctx, String suid) {
        String sql = sqlTemplate.replace("{{WHERE}}", WHERE_BY_SUID);
        return ctx.namedJdbc().query(sql, new MapSqlParameterSource("suid", suid),
                (rs, i) -> map(ctx.name(), rs));
    }

    private static StudyRow map(String cluster, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StudyRow(
                cluster,
                rs.getString("study_iuid"),
                rs.getString("series_iuid"),
                rs.getString("modality"),
                rs.getString("file_name"),
                rs.getString("storage_group_id"),
                rs.getString("storage_id"),
                rs.getString("storage_path"));
    }
}
