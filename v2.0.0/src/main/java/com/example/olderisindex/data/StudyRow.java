package com.example.olderisindex.data;

public record StudyRow(
        String cluster,
        String studyIuid,
        String seriesIuid,
        String modality,
        String fileName,
        String storageGroupId,
        String storageId,
        String storagePath
) {}
