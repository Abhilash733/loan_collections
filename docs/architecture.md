# Architecture

## Style
A single Spring Boot (Spring MVC) deployable with a classic **layered** structure: controllers → services
(interface + implementation) → repositories, with pure business rules isolated in `domain/`. MySQL 8 is managed by
Flyway; pages are server-rendered with Thymeleaf. A standard-library Python toolchain generates and validates the
synthetic dataset.

```
HTTP ──> controller/api  (REST, JSON envelope)      controller/web (Thymeleaf pages)
               │                                            │
               └──────────────> service/ (interfaces) <─────┘
                                     │  service/impl/
              domain/ (pure rules) <─┤
                                     └──> repository/ (Spring Data JPA)  repository/jdbc/ (JdbcTemplate)
                                                              │
                                                           MySQL 8 (Flyway V1-V3)

tools/collections_rules.py  <── same rules ──>  domain/CollectionsRules, LoanPosition, PriorityScoring,
                                                ContactPolicy, PromiseOutcome
data/generated/sql/seed.sql ──(admin: load dataset)──> MySQL
data/generated/ground_truth ──(read only at evaluation)──> service/impl/EvaluationServiceImpl
```

## Package structure (`com.example.loancollections`)
| Package | Contents | Rules |
|---|---|---|
| `config` | `SecurityConfig`, `AppProperties` (`@ConfigurationProperties("app")`) | All configuration; no business logic |
| `controller.api` | `*ApiController`, one per resource, under `/api/v1/**` | Thin: validate input, call one service, wrap in `ApiResponse` |
| `controller.web` | `*Controller` for Thymeleaf pages | Thin: call services, fill the model, return a view name, flash messages |
| `service` | Service interfaces (`LoanService`, `WorkQueueService`, `ContactService`, `PromiseService`, `CaseLifecycleService`, `ComplianceService`, `MetricsService`, `EvaluationService`, `SeedDataService`, `AuditService`, `CollectionCaseService`) | The contract controllers depend on |
| `service.impl` | `*ServiceImpl` | Business orchestration and transaction boundaries (`@Transactional`); no SQL |
| `repository` | Spring Data JPA repositories (`LoanRepository`, `CollectionCaseRepository`) | Entity persistence |
| `repository.jdbc` | `JdbcTemplate` repositories (read model, contact attempts, promises, preferences, case status, audit, dataset) | All SQL lives here |
| `domain` | `CollectionsRules`, `LoanPosition`, `DelinquencyCalculator`, `PriorityScoring`, `ContactPolicy`, `PromiseOutcome`, `CollectionsData` | Pure Java, no Spring, fully unit-tested |
| `model.entity` | JPA entities (`Loan`, `CollectionCase`) | Never returned from controllers |
| `model.dto.request` | Request bodies and forms with Bean Validation | |
| `model.dto.response` | API/view DTOs (`ApiResponse`, `CaseAssessment`, `RuleViolation`, `MetricsResponse`, `EvaluationReport`, ...) | Immutable records |
| `exception` | `BusinessException` (400), `ResourceNotFoundException` (404), `GlobalExceptionHandler` | Handler scoped to `controller.api`; unexpected errors logged |
| `util` | `DateUtils` (UTC conversions) | |

Resources: `templates/<area>/<view>.html` (`dashboard/`, `queue/`, `loans/`, `cases/`, `compliance/`, `admin/`)
with a shared `fragments/layout.html`; `static/css/`; `db/migration/`. Tests mirror the main packages
(`src/test/java/.../domain`).

## Conventions applied
- Constructor injection only; no field injection.
- Controllers never touch repositories or entities directly (entities are mapped to DTOs).
- Services are coded against interfaces; read-only services are `@Transactional(readOnly = true)`.
- Money is `BigDecimal` / `DECIMAL`; timestamps are UTC (`DateUtils`; the JDBC session time zone is forced to UTC).
- Balances, statuses and scores are **derived** from source records; stored values that disagree are reported as exceptions.
- State changes are explicit records: case_status_history, contact attempts (including BLOCKED ones), promise links
  and audit_events, each with the acting user.
- Configuration is externalised (`application.yml` + git-ignored `.env`); secrets are never committed.
- Flyway owns the schema; Hibernate only validates the JPA mappings.
- Ground truth stays outside the database and is read only by the evaluation service.
