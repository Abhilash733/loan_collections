# Execution Steps

How to get the Loan Collections Management System running on a new machine: from cloning the repository to signing
in and loading the synthetic dataset. Commands are given for Windows (PowerShell) and macOS/Linux (bash) where they
differ.

> Synthetic data only. Not for real customers or real money.

---

## 1. Prerequisites

Install these first. Maven is **not** required; the project ships the Maven wrapper (`mvnw` / `mvnw.cmd`), which
downloads Maven 3.9.9 on first use.

| Tool | Version | Used for | Check with |
|---|---|---|---|
| Git | any recent | cloning the repository | `git --version` |
| JDK | **21** | building and running the Spring Boot 3.5 app | `java -version` |
| MySQL Server | **8.x** | application database (schema created by Flyway) | `mysql --version` |
| Python | **3.10+** (standard library only, no `pip install` needed) | generating and validating the synthetic dataset | `python --version` |

Notes:

- `JAVA_HOME` must point to the JDK 21 install, and `java` must be on `PATH`. The wrapper uses `JAVA_HOME`.
- MySQL must be running and listening on `localhost:3306` (the default). On Windows the service is normally named
  `MySQL80`; check it with `Get-Service MySQL80` and start it with `Start-Service MySQL80` (admin shell).
- The `mysql` command-line client is only needed if you want to load the seed SQL by hand (step 7, option B).
- The first build downloads Maven and all dependencies, so internet access is needed once.

---

## 2. Clone the repository

```powershell
git clone <repository-url> loan-collections
cd loan-collections
```

Replace `<repository-url>` with the HTTPS or SSH URL of the project's Git remote. All later commands are run from
the project root (the folder that contains `pom.xml`).

What you get after cloning:

| Path | Contents |
|---|---|
| `src/main/java/...` | Spring Boot application (controllers, services, repositories, domain rules) |
| `src/main/resources/` | `application.properties`, Flyway migrations (`db/migration/V1..V3`), Thymeleaf templates, CSS |
| `src/test/java/...` | unit tests for the domain rules |
| `tools/` | Python scripts: data generator, validator, reproducibility check, shared rules |
| `docs/` | architecture, business rules, data dictionary |
| `.env.example` | template for local database settings |
| `mvnw`, `mvnw.cmd`, `.mvn/` | Maven wrapper |

These are **git-ignored** and will not be in a fresh clone; you create them in the steps below:

- `.env` (your local DB credentials) - step 4
- `data/generated/` (the synthetic dataset) - step 5
- `target/` (build output) - step 6

---

## 3. Prepare MySQL

The application connects with the credentials in `.env` and creates the `loan_collections` database automatically
(`createDatabaseIfNotExist=true` in the JDBC URL). You only need a MySQL user that is allowed to create it.

**Option A - use `root` (simplest for local development).** Nothing to do here; put the root password in `.env`.

**Option B - use a dedicated user (recommended).** Connect as root and run:

```sql
CREATE DATABASE IF NOT EXISTS loan_collections CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'loan_app'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON loan_collections.* TO 'loan_app'@'localhost';
FLUSH PRIVILEGES;
```

```powershell
mysql -u root -p          # then paste the statements above
```

---

## 4. Configure the environment (`.env`)

Copy the template and edit it:

```powershell
# Windows
Copy-Item .env.example .env
```

```bash
# macOS / Linux
cp .env.example .env
```

`.env` contents:

```properties
DB_URL=jdbc:mysql://localhost:3306/loan_collections?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&createDatabaseIfNotExist=true
DB_USERNAME=root
DB_PASSWORD=change-me
SERVER_PORT=6969
DATASET_DIR=data/generated
```

| Key | What to set |
|---|---|
| `DB_URL` | Leave as is unless MySQL is on another host/port. Keep the time-zone parameters: all timestamps are UTC. |
| `DB_USERNAME` / `DB_PASSWORD` | The MySQL user from step 3. **Must be changed** - `change-me` will not work. |
| `SERVER_PORT` | Informational. The port actually used is `server.port=6969` in `src/main/resources/application.properties`; change it there if 6969 is taken. |
| `DATASET_DIR` | Folder the app reads the generated dataset from. Leave as `data/generated`. |

How it is picked up: `application.properties` contains `spring.config.import=optional:file:.env[.properties]`, so
Spring Boot reads `.env` from the **current working directory** at startup. Always start the app from the project
root. If `.env` is missing, the app falls back to user `loan_app` / password `local-dev-password-change-me`.

`.env` is git-ignored. Never commit it.

---

## 5. Generate the synthetic dataset (Python)

The dataset is reproducible from a fixed seed, so it is generated locally instead of being stored in Git.

```powershell
python tools/generate_synthetic_data.py
```

(On macOS/Linux use `python3` if `python` points to Python 2.)

Defaults: 10,000 loans, seed `20261022`, reference date `2026-10-28`, output `data/generated`. It takes a short
while and writes:

| Output | Purpose |
|---|---|
| `data/generated/csv/`, `json/` | one file per table |
| `data/generated/sql/seed.sql` | MySQL script that replaces all business data (about 20 MB) |
| `data/generated/ground_truth/` | labelled edge cases and expected case decisions (used only by `/evaluation`) |
| `data/generated/manifest.json` | seed, command, row counts, SHA-256 checksums |
| `data/generated/validation_report.json` | result of the built-in validation run |

Optional arguments:

```powershell
python tools/generate_synthetic_data.py --loans 100                 # small starter set, faster to load
python tools/generate_synthetic_data.py --loans 10000 --seed 20261022 --reference-date 2026-10-28 --out data/generated
```

Optional checks:

```powershell
python tools/validate_dataset.py            # re-validate the export from its CSVs (--dir to point elsewhere)
python tools/check_reproducibility.py       # generate twice, compare row counts and SHA-256; writes data/reproducibility_report.json
```

Expected: validation reports all structural checks passing and 0 false positives / 0 false negatives for the 8
detection rules; the reproducibility check prints `PASS`.

---

## 6. Build and run the tests

The wrapper resolves all Maven dependencies from `pom.xml` (Spring Boot 3.5.6 starters for Web, Thymeleaf, Data JPA,
Validation, Security; Flyway + `flyway-mysql`; MySQL Connector/J; Apache Commons CSV) into `~/.m2/repository`.

```powershell
# Windows
.\mvnw.cmd clean test
```

```bash
# macOS / Linux
chmod +x mvnw      # only needed once if the execute bit was lost
./mvnw clean test
```

The unit tests cover the domain rules only (priority scoring, contact policy, promise outcomes, loan position and
lifecycle, arrears/DPD) and **do not need MySQL**. Expect `BUILD SUCCESS`.

To build a runnable JAR instead:

```powershell
.\mvnw.cmd clean package          # produces target/loan-collections-1.0.0.jar
```

---

## 7. Start the application

MySQL must be running. From the project root:

```powershell
# Windows
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
./mvnw spring-boot:run
```

Or run the packaged JAR (still from the project root, so `.env` and `data/generated` are found):

```powershell
java -jar target/loan-collections-1.0.0.jar
```

What happens on first start:

1. Spring Boot imports `.env` and connects to MySQL, creating `loan_collections` if it does not exist.
2. Flyway runs the migrations in order: `V1__create_core_schema.sql` (tables, foreign keys, checks),
   `V2__insert_demo_records.sql` (a small demo record set), `V3__collections_workflow.sql` (workflow columns and
   constraints).
3. Hibernate validates the entities against the schema (`ddl-auto=validate`).
4. The server starts on **http://localhost:6969**. Look for `Tomcat started on port 6969` in the log.

Quick check (no login needed):

```powershell
curl http://localhost:6969/api/v1/health
```

Stop the app with `Ctrl+C`.

---

## 8. Load the synthetic dataset into the database

At this point the database only has the small demo set from V2. Load the generated dataset **after** the app has
started at least once (Flyway must have created the tables). Loading replaces all business data.

**Option A - from the UI (recommended).**

1. Open http://localhost:6969 and sign in as `admin` / `dev-admin-change-me`.
2. Open **Evaluation** (`/evaluation`).
3. Click **Load synthetic dataset**. The app runs `data/generated/sql/seed.sql`.

**Option B - from the REST API.**

```powershell
curl -u admin:dev-admin-change-me -X POST http://localhost:6969/api/v1/admin/seed
```

**Option C - with the MySQL client.**

```powershell
# PowerShell does not support "<" redirection; pipe the file through cmd instead
cmd /c "mysql -u root -p loan_collections < data\generated\sql\seed.sql"
```

```bash
mysql -u root -p loan_collections < data/generated/sql/seed.sql
```

---

## 9. Use the application

Sign in with one of the built-in development users (HTTP Basic, in-memory):

| User | Password | Role | Can do |
|---|---|---|---|
| `analyst` | `dev-analyst-change-me` | ANALYST | work queue, schedule contacts, record outcomes, take and reconcile promises |
| `compliance` | `dev-compliance-change-me` | COMPLIANCE | read-only; exceptions / compliance review |
| `admin` | `dev-admin-change-me` | ADMIN | everything, including dataset load and evaluation |

Pages:

| URL | Page |
|---|---|
| `/` | dashboard (KPIs) |
| `/queue` | prioritised work queue: score breakdown, reason codes, next action, **Schedule** |
| `/loans`, `/loans/new` | loans list and create form |
| `/cases` | collection cases |
| `/exceptions` | compliance exceptions (COMPLIANCE or ADMIN) |
| `/evaluation` | dataset load and evaluation against ground truth (ADMIN) |

Suggested walkthrough after loading the dataset:

1. As `analyst`, open `/queue` and click **Schedule** on a case; a contact attempt is created at the next
   permitted slot, or stored as BLOCKED with the reason.
2. Record an outcome and take a promise-to-pay (via the API, see below).
3. Click **Reconcile promises** to settle promises past their date.
4. As `compliance`, open `/exceptions` to see rule breaches.
5. As `admin`, open `/evaluation` to see precision/recall and decision accuracy against the ground truth.

Example API calls (JSON envelope `{success, data, message, timestamp}`; the dataset reference date is `2026-10-28`):

```powershell
curl -u analyst:dev-analyst-change-me "http://localhost:6969/api/v1/work-queue?asOf=2026-10-28&limit=10"
curl -u analyst:dev-analyst-change-me "http://localhost:6969/api/v1/metrics?asOf=2026-10-28"
curl -u compliance:dev-compliance-change-me "http://localhost:6969/api/v1/compliance/exceptions?asOf=2026-10-28"
curl -u admin:dev-admin-change-me http://localhost:6969/api/v1/admin/evaluation
```

In Windows PowerShell 5.1, `curl` is an alias for `Invoke-WebRequest`; use `curl.exe` to get the real curl with
the flags above. The full endpoint list is in [README.md](README.md#8-running-it).

---

## 10. Running from an IDE (optional)

**VS Code:** install the *Extension Pack for Java* and *Spring Boot Extension Pack*, open the project folder, wait
for the Java project to import, then run `LoanCollectionsApplication` from the Spring Boot Dashboard or the
*Run* code lens on its `main` method.

**IntelliJ IDEA:** *File → Open* the folder (or `pom.xml`), set the Project SDK to 21, then run
`LoanCollectionsApplication`.

In either IDE, make sure the run configuration's **working directory is the project root**, otherwise `.env` and
`data/generated` are not found.

---

## 11. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Access denied for user ...` at startup | Wrong `DB_USERNAME` / `DB_PASSWORD` in `.env`, or `.env` not in the working directory. Start from the project root. |
| `Communications link failure` / connection refused | MySQL is not running or not on `localhost:3306`. Start the service (`Start-Service MySQL80` on Windows) or fix `DB_URL`. |
| `Port 6969 was already in use` | Another process (often a previous run) holds the port. Stop it, or change `server.port` in `application.properties`. |
| `JAVA_HOME is not defined correctly` / `release version 21 not supported` | Install JDK 21 and point `JAVA_HOME` at it; open a new terminal. |
| Flyway `Validate failed` / checksum mismatch | A migration file was edited after it ran. For a disposable local DB: `DROP DATABASE loan_collections;` and restart the app. |
| Hibernate `Schema-validation: missing column` | Migrations did not run against this database. Check the Flyway lines in the log; drop and recreate the local DB if needed. |
| **Load synthetic dataset** fails with "file not found" | `data/generated/sql/seed.sql` does not exist. Run step 5 first, and start the app from the project root. |
| Python `SyntaxError` or unknown arguments | Python is older than 3.10, or `python` is Python 2. Use `python3` / `py -3`. |
| `.\mvnw.cmd` cannot download Maven | No internet or a proxy blocks `repo.maven.apache.org`. Configure the proxy in `~/.m2/settings.xml` or allow the host. |
| Browser keeps the old login | HTTP Basic credentials are cached by the browser; close all browser windows or use a private window to switch users. |

---

## 12. Quick reference

```powershell
git clone <repository-url> loan-collections; cd loan-collections
Copy-Item .env.example .env                      # edit DB_USERNAME / DB_PASSWORD
python tools/generate_synthetic_data.py          # creates data/generated/
.\mvnw.cmd clean test                            # unit tests (no DB needed)
.\mvnw.cmd spring-boot:run                       # http://localhost:6969, Flyway builds the schema
# sign in as admin -> Evaluation -> Load synthetic dataset
```

The development users, fixed passwords and disabled CSRF protection are for local use only. See section 7 of
[README.md](README.md#7-security) before deploying anywhere shared.
