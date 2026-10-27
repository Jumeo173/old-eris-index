package com.example.olderisindex.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResearchResponse {
    private String an;
    private String suid;
    private String status;
    private List<StudyResult> studies;
    private String error;
    private Instant processedAt;

    public static ResearchResponse ok(String an, String suid, List<StudyResult> studies) {
        ResearchResponse r = new ResearchResponse();
        r.an = an; r.suid = suid; r.status = "OK";
        r.studies = studies; r.processedAt = Instant.now();
        return r;
    }
    public static ResearchResponse notFound(String an, String suid) {
        ResearchResponse r = new ResearchResponse();
        r.an = an; r.suid = suid; r.status = "NOT_FOUND"; r.processedAt = Instant.now();
        return r;
    }
    public static ResearchResponse error(String an, String suid, String msg) {
        ResearchResponse r = new ResearchResponse();
        r.an = an; r.suid = suid; r.status = "ERROR"; r.error = msg; r.processedAt = Instant.now();
        return r;
    }
    public String getAn() { return an; }
    public void setAn(String an) { this.an = an; }
    public String getSuid() { return suid; }
    public void setSuid(String suid) { this.suid = suid; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<StudyResult> getStudies() { return studies; }
    public void setStudies(List<StudyResult> studies) { this.studies = studies; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
