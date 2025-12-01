CREATE TABLE ofPushNotiService (
  username              NVARCHAR(64)     NOT NULL,
  service               NVARCHAR(1024)   NOT NULL,
  node                  NVARCHAR(1024)   NOT NULL,
  options			    NVARCHAR(MAX)    NULL
);
CREATE INDEX ofPushNoti_idx ON ofPushNotiService (username);

INSERT INTO ofVersion (name, version) VALUES ('pushnotification', 2);
