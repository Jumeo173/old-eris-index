SELECT
    st.study_iuid,
    se.series_iuid,
    se.modality,
    i.sop_iuid        AS file_name,
    l.storage_group_id,
    l.storage_id,
    l.storage_path
FROM study st
JOIN series se ON se.study_fk = st.pk
JOIN instance i ON i.series_fk = se.pk
JOIN rel_instance_location rel ON rel.instance_fk = i.pk
JOIN location l ON l.pk = rel.location_fk
WHERE {{WHERE}}
ORDER BY st.study_iuid, se.series_iuid, i.sop_iuid, l.storage_path
