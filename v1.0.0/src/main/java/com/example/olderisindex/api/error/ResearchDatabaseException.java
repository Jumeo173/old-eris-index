package com.example.olderisindex.api.error;

public class ResearchDatabaseException extends RuntimeException {
    private final String an;
    private final String suid;
    public ResearchDatabaseException(String an, String suid, Throwable cause) {
        super("Database error for " + criteria(an, suid), cause);
        this.an = an; this.suid = suid;
    }
    private static String criteria(String an, String suid) {
        if (an != null && suid != null) return "AN=" + an + ", SUID=" + suid;
        return an != null ? "AN=" + an : "SUID=" + suid;
    }
    public String getAn() { return an; }
    public String getSuid() { return suid; }
}
