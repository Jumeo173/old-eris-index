package com.example.olderisindex.api.error;

public class StudyNotFoundException extends RuntimeException {
    private final String an;
    private final String suid;
    public StudyNotFoundException(String an, String suid) {
        super(buildMsg(an, suid)); this.an = an; this.suid = suid;
    }
    private static String buildMsg(String an, String suid) {
        if (an != null && suid != null)
            return "No common instances between AN '" + an + "' and SUID '" + suid + "'";
        return an != null
                ? "Study with AN '" + an + "' not found"
                : "Study with SUID '" + suid + "' not found";
    }
    public String getAn() { return an; }
    public String getSuid() { return suid; }
}
