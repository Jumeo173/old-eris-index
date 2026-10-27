package com.example.olderisindex.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public class ResearchRequest {

    @Size(max = 64, message = "AN слишком длинный")
    private String an;

    @Size(max = 64, message = "SUID слишком длинный")
    private String suid;

    @AssertTrue(message = "Укажите хотя бы одно поле: 'an' или 'suid'")
    public boolean isAtLeastOneProvided() {
        return (an != null && !an.isBlank()) || (suid != null && !suid.isBlank());
    }

    public String getAn() { return an; }
    public void setAn(String an) { this.an = an; }
    public String getSuid() { return suid; }
    public void setSuid(String suid) { this.suid = suid; }
}
