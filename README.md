# 🚕 RideFlow

### Event-Driven Ride-Hailing Prototype

RideFlow is a local ride-hailing prototype that processes driver GPS updates and ride requests asynchronously. It combines Firebase authentication, MQTT messaging, city-specific queues, and Redis geospatial matching.

> 🎓 Learning and demonstration project. GPS is simulated, and payments do not charge real money.

---

## ✨ Implemented Features

| Feature                    | Description                                                         |
| -------------------------- | ------------------------------------------------------------------- |
| 🔐 Firebase Authentication | Email/password registration and login with backend JWT verification |
| 📍 Driver GPS Simulator    | Publishes simulated coordinates through authenticated MQTT          |
| 🛡️ MQTT Access Control     | Restricts provisioned drivers to their permitted location topics    |
| 📨 Asynchronous Processing | Buffers ride requests using RabbitMQ                                |
| 🌆 City Queues             | Separate matching queues for Colombo and Kandy                      |
| ⚡ Geospatial Matching     | Searches Redis for nearby drivers within a 5 km radius              |
| ⏱️ Location Freshness      | Excludes driver locations older than 30 seconds                     |
| 🚗 Driver Reservation      | Database constraints prevent simultaneous driver assignments        |
| 🔄 Request Idempotency     | Repeated requests with the same key return the same ride            |
| 📡 Live Updates            | Owner-authorized WebSocket tickets with polling fallback            |
| 💳 Mock Payments           | Repeated payment calls return the existing payment receipt          |
| 🗺️ Trip History            | Stores coarse pickup areas using PostgreSQL and PostGIS             |
| 🔒 Location Minimization   | Clears precise pickup coordinates when a trip is completed          |
| 💾 Ride Restoration        | Restores the selected ride after reloading the same browser tab     |
| 🩺 Health Checks           | Spring Boot Actuator reports infrastructure health                  |
| 📊 Metrics and Dashboards  | Prometheus metrics and provisioned Grafana panels                   |
| 🚨 Availability Alert      | Backend scrape-failure alert with verified recovery                 |
| 🧪 Automated Checks        | API, concurrency and Redis tests plus CI validation                 |
| 🐳 Backend Container       | Docker image build and Compose runtime                              |

---

## 🏗️ Architecture

```mermaid
flowchart TD
    subgraph Client["Browser Dashboard"]
        Driver["📍 Driver Simulator"]
        Rider["🙋 Rider Interface"]
    end
    Firebase["🔐 Firebase Authentication"]
    MQTT["📡 Mosquitto MQTT"]
    Ingestion["GPS Ingestion"]
    Redis[("⚡ Redis GEO")]
    API["Spring Boot REST API"]
    DB[("🗄️ PostgreSQL + PostGIS")]
    Outbox["Outbox Publisher"]
    Rabbit["📨 RabbitMQ"]
    Colombo["Colombo Matching Workers"]
    Kandy["Kandy Matching Workers"]
    Updates["🔄 WebSocket Updates"]
    Driver -->|"Credentials + GPS"| MQTT
    MQTT --> Ingestion
    Ingestion --> Redis
    Rider -->|"Sign in"| Firebase
    Firebase -->|"ID token"| Rider
    Rider -->|"Bearer token + ride request"| API
    API -->|"Ride + outbox transaction"| DB
    DB --> Outbox
    Outbox -->|"Confirmed publication"| Rabbit
    Rabbit -->|"rides.colombo"| Colombo
    Rabbit -->|"rides.kandy"| Kandy
    Colombo -->|"Nearby drivers"| Redis
    Kandy -->|"Nearby drivers"| Redis
    Colombo -->|"Reserve driver and update ride"| DB
    Kandy -->|"Reserve driver and update ride"| DB
    Colombo --> Updates
    Kandy --> Updates
    API -->|"Owner-authorized ticket"| Rider
    Updates -->|"Ride status notification"| Rider
```

### How a ride is processed

1. A provisioned driver publishes GPS coordinates through MQTT.

2. The backend updates the driver's city-specific Redis location and last-seen timestamp.

3. An authenticated rider submits a request with an idempotency key.

4. The backend saves the ride and its outbox event in one database transaction.

5. The outbox publisher sends the event to the appropriate city queue.

6. A matching worker checks nearby, fresh driver locations.

7. The worker reserves an available driver and updates the ride.

8. The dashboard receives a notification and fetches the latest ride status.

9. Completing the ride releases the assignment and archives a coarse pickup area.

10. Mock payment retries return the existing receipt.

---

## 🔄 Ride Lifecycle

```mermaid
stateDiagram-v2
    [*] --> QUEUED: Request accepted
    QUEUED --> MATCHED: Available driver found
    QUEUED --> NO_DRIVER: No eligible driver found
    MATCHED --> COMPLETED: Complete trip
    COMPLETED --> COMPLETED: Repeat completion request
    NO_DRIVER --> [*]
    COMPLETED --> [*]
```

Payment is tracked separately from the ride status. A completed ride can receive a `MOCK_SUCCEEDED` payment record.

---

## 🧰 Technology Stack

| Layer                | Technology                                                                              |
| -------------------- | --------------------------------------------------------------------------------------- |
| Backend              | Java 17, Spring Boot 4.0.8                                                              |
| Authentication       | Firebase Authentication, Spring Security JWT verification                               |
| Driver Messaging     | MQTT, Eclipse Mosquitto, MQTT.js                                                        |
| Request Queues       | RabbitMQ, Spring AMQP                                                                   |
| Location Cache       | Redis geospatial indexes                                                                |
| Database             | PostgreSQL 16, PostGIS                                                                  |
| Live Updates         | WebSockets, HTTP polling fallback                                                       |
| Frontend             | HTML, CSS, JavaScript                                                                   |
| Local Infrastructure | Docker Desktop, Docker Compose                                                          |
| Build                | Maven Wrapper                                                                           |
| Monitoring           | Spring Boot Actuator, Micrometer, Prometheus, Grafana                                   |
| CI                   | GitHub Actions: backend tests, JAR packaging, Docker image build, Prometheus validation |

---

## 🚀 Local Setup — Windows PowerShell

### 1. Prerequisites

Install:

- Git

- Java 17 JDK

- Docker Desktop with the WSL 2 backend

- A code editor such as VS Code

A separate Maven installation is unnecessary because the backend includes Maven Wrapper.

### 2. Clone the repository

```powershell
git clone https://github.com/HasinduBimsara/rideflow.git
cd rideflow
```

### 3. Configure Firebase

Use your Firebase project configuration in the frontend authentication files and configure the backend JWT issuer and audience for the same project.

Enable **Email/Password** authentication in Firebase and configure the authorized domains used by your local application.

Firebase browser configuration identifies the project. It is separate from private service-account credentials and MQTT passwords.

### 4. Provision MQTT accounts

The broker uses a local password file:

```text
mosquitto/secrets/passwords
```

This file is ignored by Git and must be provisioned locally before starting the MQTT service.

Create:

- An `ingestion` account for the backend.

- A driver account whose username matches the driver's Firebase UID.

Use `mosquitto_passwd` to generate password hashes. Configure `mosquitto/acl.conf` so:

- `ingestion` can read `drivers/+/+/location`.

- Each driver can write only to its explicitly permitted city and UID topic.

Example driver topic:

```text
drivers/colombo/<FIREBASE_UID>/location
```

> MQTT accounts are provisioned manually. Registering a Firebase account does not automatically create an MQTT account. A fresh clone requires this local provisioning step.

### 5. Start the full Docker stack

Open Docker Desktop and wait for its engine to start. From the repository root, enter the password provisioned for the MQTT `ingestion` account:

```powershell
$secret = Read-Host "Enter ingestion MQTT password" -AsSecureString
$env:RIDEFLOW_MQTT_PASSWORD = [System.Net.NetworkCredential]::new("", $secret).Password
Remove-Variable secret

docker compose -f compose.yaml -f compose.backend.yaml -f compose.monitoring.yaml config --quiet
```

If validation succeeds, start the services:

```powershell
docker compose -f compose.yaml -f compose.backend.yaml -f compose.monitoring.yaml up -d --build

docker compose -f compose.yaml -f compose.backend.yaml -f compose.monitoring.yaml ps
```

The password variable applies to the current PowerShell session. Set it again when using a new terminal. Never commit MQTT passwords or the local password file.

### 6. Backend runtime

The backend runs in Docker with the stack above. Do not also launch a native backend on port `8081`.

For optional native Java development, the repository includes `start-backend.ps1`. Stop the Docker backend first and configure native Redis access to host port `16379`; Docker-to-Docker connections continue to use `redis:6379`.

### 7. Open the dashboard

- Dashboard: http://localhost:8081/

- Login: http://localhost:8081/login.html

- Health: http://localhost:8081/actuator/health

---

## 🎮 Try the Demo

### 📍 Start driver GPS

1. Register or sign in.

2. Enter the MQTT password provisioned for that Firebase UID.

3. Select **Colombo**.

4. Keep the default driver coordinates:
   - Latitude: `6.9271`

   - Longitude: `79.8612`

5. Click **Start GPS**.

The current simulator is configured for Colombo driver publishing. Kandy driver access requires corresponding ACL and simulator configuration changes.

### 🙋 Request a ride

1. Select **Colombo**.

2. Enter pickup coordinates:
   - Latitude: `6.9280`

   - Longitude: `79.8620`

3. Click **New ride**.

4. An available, fresh nearby driver should produce `MATCHED`.

Without an eligible driver, the result is `NO_DRIVER`.

### ✅ Complete and pay

1. Click **Complete trip**.

2. Confirm the ride becomes `COMPLETED`.

3. Confirm precise pickup `lat` and `lon` become `null`.

4. Click **Mock pay / retry payment**.

5. Repeat the payment call and confirm the receipt remains unchanged.

### 💾 Reload the page

Reload the same tab after creating a ride. The dashboard restores the saved ride ID and fetches its current details through the authenticated API.

GPS publishing must be restarted manually after reload. Passwords and payment details are not restored automatically.

---

## 🔌 Ride API

Ride endpoints require a Firebase ID token:

```http
Authorization: Bearer <FIREBASE_ID_TOKEN>
```

| Method | Endpoint                        | Purpose                               |
| ------ | ------------------------------- | ------------------------------------- |
| `POST` | `/api/rides`                    | Create or retry a ride request        |
| `GET`  | `/api/rides/{id}`               | Read an owned ride                    |
| `POST` | `/api/rides/{id}/complete`      | Complete an owned matched ride        |
| `POST` | `/api/rides/{id}/pay`           | Create or retrieve a mock payment     |
| `POST` | `/api/rides/{id}/socket-ticket` | Obtain a short-lived WebSocket ticket |

Creating a ride also requires:

```http
Idempotency-Key: <UNIQUE_REQUEST_KEY>
```

Example request body:

```json
{
  "city": "colombo",
  "lat": 6.928,
  "lon": 79.862
}
```

The backend derives the rider identity from the verified token.

---

## 🛡️ Security and Privacy

- Firebase JWT verification protects ride APIs.

- Ownership checks restrict ride reads, completion, payment, and socket-ticket creation.

- WebSocket tickets are short-lived and single-use.

- MQTT anonymous connections are disabled.

- MQTT ACLs restrict driver publishing topics.

- Password files are excluded from version control.

- Local infrastructure ports are bound to loopback addresses.

- Precise pickup coordinates are cleared after trip completion.

- Mock payments do not collect or process card details.

> The local MQTT listeners use unencrypted connections. Production deployment requires TLS, secure secret management, and further security hardening. Coarse location data remains potentially sensitive.

---

## 🧪 Manually Verified Scenarios

| Scenario                                          | Observed Result                      |
| ------------------------------------------------- | ------------------------------------ |
| Valid driver MQTT credentials                     | GPS reaches Redis                    |
| Anonymous MQTT connection                         | Rejected                             |
| Driver publishes to another driver's topic        | Rejected with MQTT reason code `135` |
| Another user reads, completes, or pays for a ride | HTTP `404`                           |
| Another user requests a ride's socket ticket      | HTTP `404`                           |
| Same request key and payload                      | Existing ride returned               |
| Same request key with changed payload             | HTTP `409`                           |
| Repeated mock payment                             | Same receipt returned                |
| Trip completion                                   | Precise pickup coordinates cleared   |
| Matching listeners stopped                        | Request remains queued               |
| Matching listeners restarted                      | Queued request processed             |
| Dashboard reload in the same tab                  | Previous selected ride restored      |

The listener restart test verifies backlog processing after a temporary worker outage. It does not demonstrate an abrupt crash during an active transaction or a RabbitMQ storage failure.

---

## 🩺 Local Services

| Service              | Local Port                  |
| -------------------- | --------------------------- |
| RideFlow Backend     | `8081`                      |
| PostgreSQL / PostGIS | `5432`                      |
| Redis                | `16379` (container: `6379`) |
| RabbitMQ AMQP        | `5672`                      |
| RabbitMQ Management  | `15672`                     |
| MQTT TCP             | `1883`                      |
| MQTT WebSocket       | `9001`                      |
| Prometheus           | `9090`                      |
| Grafana              | `3000`                      |

Useful commands:

```powershell
# Show infrastructure status
docker compose -f compose.yaml -f compose.backend.yaml -f compose.monitoring.yaml ps
# Show MQTT logs
docker compose logs --tail=30 mqtt
# Inspect queue depth and consumers
docker compose exec -T rabbitmq rabbitmqctl list_queues -p / name messages_ready messages_unacknowledged consumers
# Stop infrastructure while retaining volumes
docker compose -f compose.yaml -f compose.backend.yaml -f compose.monitoring.yaml stop
```

---

## 📊 Monitoring and Alerts

Prometheus scrapes `/actuator/prometheus` every 15 seconds. Grafana loads the datasource and dashboard from version-controlled provisioning files.

| Dashboard panel                               | Meaning                                                                                                                |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Backend Status                                | Prometheus scrape availability: `1` means successful, `0` means failed; this is not a complete dependency-health check |
| Matching Results Since Backend Startup        | Completed matching attempts grouped by city and outcome (`MATCHED` or `NO_DRIVER`)                                     |
| Average Matching Processing Time              | Five-minute rate-based mean processing duration in milliseconds, excluding queue waiting time                          |
| Stale Locations Removed Since Backend Startup | Removed stale driver locations grouped by city                                                                         |

The average processing-time graph may be empty without recent matching attempts. Backend counters reset when the backend restarts.

- Grafana: http://localhost:3000 — open **RideFlow Monitoring**.
- Prometheus targets: http://localhost:9090/targets
- Prometheus alerts: http://localhost:9090/alerts
- Backend health: http://localhost:8081/actuator/health

```mermaid
flowchart TD
    Backend["Spring Boot metrics"] -->|"Scrape every 15s"| Prometheus["Prometheus"]
    Prometheus --> Grafana["Grafana dashboard"]
    Prometheus --> Rule["Availability rule"]
    Rule -->|"Failed scrapes for 1 minute"| Alert["Firing alert in Prometheus"]
```

`RideFlowBackendUnavailable` uses `up{job="rideflow-backend"} == 0` with `for: 1m`. It detects failed scrapes of the configured backend target, which can result from a backend outage or connectivity/metrics-endpoint failure. Email and other external notifications are not configured.

The stop-and-restart test was manually verified: **Inactive → Pending → Firing → Inactive**.

| File                                                         | Purpose                                    |
| ------------------------------------------------------------ | ------------------------------------------ |
| `compose.monitoring.yaml`                                    | Prometheus and Grafana services and mounts |
| `monitoring/prometheus/prometheus.yml`                       | Scrape and rule-file configuration         |
| `monitoring/prometheus/alerts.yml`                           | Backend availability alert                 |
| `monitoring/grafana/provisioning/datasources/prometheus.yml` | Prometheus datasource                      |
| `monitoring/grafana/provisioning/dashboards/dashboards.yml`  | Dashboard provider                         |
| `monitoring/grafana/dashboards/rideflow-monitoring.json`     | Dashboard panels and queries               |

UI dashboard edits do not update the JSON in Git. Export and update that file to preserve future dashboard changes in the repository.

## 🧪 Automated Tests and CI

GitHub Actions runs on pushes and pull requests to `main`, and supports manual execution.

- **Test and package backend:** starts disposable PostgreSQL/PostGIS, Redis, RabbitMQ and authenticated MQTT infrastructure; runs Maven `clean verify`; builds the backend Docker image.
- **Validate Prometheus configuration and alerts:** uses `promtool check config` and `promtool check rules` with Prometheus `v3.5.0`.

Automated coverage includes API ownership, request and mock-payment idempotency, completion behavior, concurrent driver reservation, and matching against real Redis geospatial data. JWT-based API tests do not replace verification of Firebase token cryptography.

Prometheus CI checks validate configuration and rule syntax. The availability alert's firing and recovery were verified manually; CI does not currently simulate an outage. Building the Docker image does not publish or deploy it.

---

## ⚠️ Current Limitations

- This is a local prototype, not a production ride-hailing service.

- Rider and driver roles are not yet separated.

- A demo account can currently act as both rider and driver.

- MQTT driver accounts and permissions are provisioned manually.

- Matching uses geographic distance rather than road travel time.

- Separate city queues provide logical separation, but share backend and infrastructure resources.

- Payments are simulated; no payment gateway is connected.

- The frontend is a browser dashboard, not a React Native application.

- Automated scaling, cloud deployment, and multi-region failover are not implemented.

- Monitoring currently covers backend metrics and scrape availability; broader infrastructure monitoring and load testing remain future work.
- Availability alerts are displayed in Prometheus; external notification delivery is not configured.

---

## 🛣️ Roadmap

- [ ] Separate rider and driver permissions

- [x] Add automated API, concurrency, and real Redis matching tests

- [x] Add matching processing-time and stale-location metrics
- [x] Provision a Grafana dashboard and backend availability alert
- [ ] Add queue-age metrics

- [x] Add CI checks with GitHub Actions

- [x] Containerize the backend

- [ ] Add TLS for MQTT and application traffic

- [ ] Integrate a payment gateway in test mode

- [ ] Add road-aware ETA matching

- [ ] Evaluate cloud deployment and autoscaling

- [ ] Build dedicated mobile clients

---

## 👨‍💻 Author

**Hasindu Bimsara**

Computing & Information Systems undergraduate

Sabaragamuwa University of Sri Lanka

- [GitHub](https://github.com/HasinduBimsara)

- [LinkedIn](https://www.linkedin.com/in/hasindubimsara/)

- [RideFlow Repository](https://github.com/HasinduBimsara/rideflow)
