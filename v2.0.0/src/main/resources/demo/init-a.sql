CREATE TABLE IF NOT EXISTS study (
    pk BIGINT PRIMARY KEY,
    study_iuid VARCHAR(255) NOT NULL,
    accession_no VARCHAR(255) NOT NULL
);
CREATE TABLE IF NOT EXISTS series (
    pk BIGINT PRIMARY KEY,
    study_fk BIGINT NOT NULL,
    series_iuid VARCHAR(255) NOT NULL,
    modality VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS instance (
    pk BIGINT PRIMARY KEY,
    series_fk BIGINT NOT NULL,
    sop_iuid VARCHAR(255) NOT NULL
);
CREATE TABLE IF NOT EXISTS location (
    pk BIGINT PRIMARY KEY,
    storage_group_id VARCHAR(255) NOT NULL,
    storage_id VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL
);
CREATE TABLE IF NOT EXISTS rel_instance_location (
    instance_fk BIGINT NOT NULL,
    location_fk BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS dcm4che_config (
    path VARCHAR(255) PRIMARY KEY,
    content VARCHAR(1000000) NOT NULL
);

MERGE INTO dcm4che_config KEY(path) VALUES (
  '/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc',
  '{"deviceExtensions":{"StorageDeviceExtension":{"Storage System Groups":[{"dcmStorageSystemGroupID":"G1","Storage Systems":[{"dcmStorageSystemID":"ST1","dcmStorageSystemPath":"/dicom/archive-A"}]}]}}}'
);

MERGE INTO study KEY(pk)    VALUES (1, 'U1', 'ACC12345');
MERGE INTO series KEY(pk)   VALUES (1, 1, 'S1', 'CT'), (2, 1, 'S2', 'MR');
MERGE INTO instance KEY(pk) VALUES
  (1, 1, 'I1'),
  (2, 1, 'I2'),
  (3, 2, 'I3');
MERGE INTO location KEY(pk) VALUES
  (1, 'G1', 'ST1', '2024/01/study_a.zip'),
  (2, 'G1', 'ST1', '2024/01/study_a.zip'),
  (3, 'G1', 'ST1', '2024/01/study_a2.zip');
MERGE INTO rel_instance_location KEY(instance_fk, location_fk) VALUES
  (1, 1), (2, 2), (3, 3);
