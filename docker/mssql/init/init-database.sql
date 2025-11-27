CREATE SEQUENCE seq_centraxx_consent_change_id START WITH 1 INCREMENT BY 1;

create table centraxx_consent
(
    id               int IDENTITY(1,1) primary key,
    oid              int,
    patientcontainer int,
    change_id        int DEFAULT NEXT VALUE FOR seq_centraxx_consent_change_id,
    change_kind      varchar(10)                           not null,
    change_date      datetime2   default SYSUTCDATETIME() not null,
    change_user      varchar(20) default CURRENT_USER      not null
);

CREATE SEQUENCE seq_centraxx_sample_change_id START WITH 1 INCREMENT BY 1;

create table centraxx_sample
(
    id               int IDENTITY(1,1) primary key,
    oid              int,
    patientcontainer int,
    consent          int,
    dtype            varchar(20),
    change_id        int DEFAULT NEXT VALUE FOR seq_centraxx_sample_change_id,
    change_kind      varchar(10)                           not null,
    change_date      datetime2   default SYSUTCDATETIME() not null,
    change_user      varchar(20) default CURRENT_USER      not null
);