CREATE TABLE ofPushNotiService (
  username              VARCHAR(64)     NOT NULL,
  service               VARCHAR(1024)   NOT NULL,
  node                  VARCHAR(1024)   NOT NULL,
  options			    LONGVARCHAR     NULL
);
CREATE INDEX ofPushNoti_idx ON ofPushNotiService (username);

INSERT INTO ofVersion (name, version) VALUES ('pushnotification', 1);
