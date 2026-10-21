CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS rides (
    id UUID PRIMARY KEY,
    rider_id VARCHAR(100) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    city VARCHAR(30) NOT NULL,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    driver_id VARCHAR(100),
    fare_minor INTEGER NOT NULL DEFAULT 15000,
    currency VARCHAR(3) NOT NULL DEFAULT 'lkr',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (rider_id, request_key),

    CHECK (city IN ('colombo', 'kandy')),
    CHECK (lat BETWEEN -85 AND 85),
    CHECK (lon BETWEEN -180 AND 180),
    CHECK (fare_minor >= 0),
    CHECK (
        status IN ('QUEUED', 'MATCHED', 'NO_DRIVER', 'COMPLETED')
    )
);

CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    ride_id UUID NOT NULL REFERENCES rides(id),
    city VARCHAR(30) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx
ON outbox(id)
WHERE published = FALSE;

CREATE TABLE IF NOT EXISTS assignments (
    driver_id VARCHAR(100) PRIMARY KEY,
    ride_id UUID NOT NULL UNIQUE REFERENCES rides(id)
);

CREATE TABLE IF NOT EXISTS payments (
    ride_id UUID PRIMARY KEY REFERENCES rides(id),
    payment_key VARCHAR(100) NOT NULL UNIQUE,
    receipt UUID NOT NULL UNIQUE,
    amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS trip_history (
    ride_id UUID PRIMARY KEY REFERENCES rides(id),
    city VARCHAR(30) NOT NULL,
    pickup_area GEOMETRY(POLYGON, 4326) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);