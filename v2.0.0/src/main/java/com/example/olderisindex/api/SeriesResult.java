package com.example.olderisindex.api;

import java.util.List;

public record SeriesResult(String uid, String modality, List<InstanceResult> instances) {}
