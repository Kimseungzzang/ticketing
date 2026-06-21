-- auth_db schema
CREATE DATABASE auth_db;
\c auth_db

CREATE TABLE IF NOT EXISTS users (
    id       VARCHAR(255) PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL
);
