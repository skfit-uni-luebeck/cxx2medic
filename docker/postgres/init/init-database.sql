create table centraxx_sample
(
    id               serial
        primary key,
    oid              integer,
    patientcontainer integer,
    consent          integer,
    dtype            varchar(20),
    change_id        serial,
    change_kind      varchar(10)                           not null,
    change_date      timestamp   default CURRENT_TIMESTAMP not null,
    change_user      varchar(20) default CURRENT_USER      not null
);

alter table centraxx_sample
    owner to postgres;

create table centraxx_consent
(
    id               serial
        primary key,
    oid              integer,
    patientcontainer integer,
    change_id        serial,
    change_kind      varchar(10)                           not null,
    change_date      timestamp   default CURRENT_TIMESTAMP not null,
    change_user      varchar(20) default CURRENT_USER      not null
);

alter table centraxx_consent
    owner to postgres;