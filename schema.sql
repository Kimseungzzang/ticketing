-- booking_db schema
CREATE DATABASE booking_db;
\c booking_db

CREATE TABLE IF NOT EXISTS bookings (
    id         UUID PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL,
    event_id   VARCHAR(255) NOT NULL,
    seat_id    VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS seats (
    id           VARCHAR(255) PRIMARY KEY,       -- "{eventId}:{seatId}"
    event_id     VARCHAR(255) NOT NULL,
    seat_id      VARCHAR(50)  NOT NULL,           -- "S-A-1"
    section_id   VARCHAR(10)  NOT NULL,           -- "S"
    section_name VARCHAR(50)  NOT NULL,           -- "S석"
    row          VARCHAR(5)   NOT NULL,
    number       INTEGER      NOT NULL,
    price        INTEGER      NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
                 CHECK (status IN ('AVAILABLE', 'TAKEN'))
);

CREATE INDEX IF NOT EXISTS idx_seats_event_id ON seats (event_id);
CREATE INDEX IF NOT EXISTS idx_bookings_user_id ON bookings (user_id);
