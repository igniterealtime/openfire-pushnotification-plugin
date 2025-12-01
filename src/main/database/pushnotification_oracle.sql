CREATE TABLE ofPushNotiService (
  username              VARCHAR2(64)    NOT NULL,
  service               VARCHAR2(1024)  NOT NULL,
  node                  VARCHAR2(1024)  NOT NULL,
  options			    CLOB            NULL
);
CREATE INDEX ofPushNoti_idx ON ofPushNotiService (username);

INSERT INTO ofVersion (name, version) VALUES ('pushnotification', 2);
