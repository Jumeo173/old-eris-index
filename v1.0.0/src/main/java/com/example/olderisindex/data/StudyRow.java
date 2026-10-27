package com.example.olderisindex.data;

public record StudyRow(
        String studyIuid, String seriesIuid, String modality, String fileName,
        String storageGroupId, String storageId, String storagePath) {}
