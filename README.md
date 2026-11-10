# Clothes Advisor

REST-сервис для подбора одежды из личного гардероба с учётом погоды. Учебный проект по [курсу высоконагруженных систем](https://github.com/Discipliny/highload_systems).

- **USER** загружает вещи и фотографии, составляет образы вручную или через AI.
- **STYLIST** с разрешения владельца просматривает гардероб, создаёт образы и оценивает чужие.
- **ADMIN** создаёт аккаунты, задаёт пароли, управляет ролями и блокировками, модерирует фотографии и образы.

Стек: Kotlin, Spring Boot, PostgreSQL, Spring Data JDBC/R2DBC, Flyway, Yandex Object Storage и OpenAI. В ЛР2 API гардероба находится в `wardrobe-service`, AI-подбор — в `recommendation-service`, пользователи, их история и разрешения стилистам — в `account-service` с R2DBC и отдельной БД. Gateway направляет запросы аккаунтов в account; wardrobe проверяет пользователей и разрешения через AccountClient. Пакеты разделены по техническим слоям, внутри — по предметным областям.

Несекретные настройки сервисов находятся в `config-repository/<service>/`. Config Server выбирает каталог по имени приложения; профильные файлы лежат рядом с основным. Compose монтирует репозиторий только для чтения. Пароли и credentials остаются в переменных окружения и secret mounts, настройки подключения к Config Server — в самих приложениях. При недоступном Config Server сервис не запускается.

Wardrobe автоматически проверяет клиентские настройки каждые 30 секунд: таймауты account/recommendation, лимит ответа account и параметры Circuit Breaker меняются без перезапуска. Recommendation так же обновляет промпт подбора. Неверная конфигурация не заменяет рабочую; изменение политики breaker сбрасывает только его состояние и статистику. Остальные настройки применяются при перезапуске. Подробности — в [архитектуре](docs/lab2/architecture.md).

Сначала запускается Config Server, затем Eureka. Бизнес-сервисы регистрируются под своими именами. При первом запуске Gateway может кратковременно возвращать 503, пока обновляется реестр Eureka; healthcheck не проверяет готовность всех маршрутов. Интерфейс Eureka доступен локально: http://localhost:8761.

В ЛР3 добавлены Spring Security и вход по логину и паролю. API использует JWT, а текущие роли и блокировки проверяются через account. Access token действует 15 минут; refresh-сессия — 7 дней с момента входа. Создавать аккаунты может только ADMIN. [Решения и ограничения авторизации](docs/lab3/theory.md).

В ЛР4 пользователь отправляет подбор образа, замену вещей или создание капсулы в фон. Wardrobe возвращает `202` со ссылкой на задание. Recommendation получает команду через Kafka, выполняет подбор и отправляет результат в wardrobe. О завершении пользователь узнаёт в разделе «Уведомления». Туда же приходят запросы и ответы стилистов. Фотографии принимает отдельный file-service. [Изменения ЛР4](docs/lab4/changes.md) · [Приёмка и сценарий защиты](docs/lab4/acceptance.md)

## Быстрый запуск

Нужны Docker с Compose 2.20.3+, Python 3 и OpenSSL для первичной подготовки Kafka, а также приватный бакет Yandex Object Storage с доступом на чтение и загрузку файлов.

Если `.env` ещё нет, создайте его из примера:

```shell
cp .env.example .env
```

Заполните в `.env`:

- `S3_BUCKET` — имя приватного бакета, в котором file-service хранит фотографии.
- `YANDEX_SERVICE_ACCOUNT_KEY_FILE` — абсолютный путь к JSON-файлу авторизованного ключа сервисного аккаунта с правами чтения и загрузки объектов в этот бакет. Храните файл вне репозитория и контекста сборки Docker; он должен быть доступен для чтения пользователю `APP_UID`.
- `DB_PASSWORD` — пароль PostgreSQL гардероба.
- `ACCOUNT_DB_PASSWORD` — отдельный пароль PostgreSQL аккаунтов. Оба значения из примера подходят только для локального запуска.
- `FILE_DB_PASSWORD`, `RECOMMENDATION_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` — пароли соответствующих БД PostgreSQL. Заполните все три в дополнение к паролям wardrobe и account.
- `FILE_WARDROBE_PASSWORD_FILE` и `FILE_RECOMMENDATION_PASSWORD_FILE` — абсолютные пути к файлам с разными паролями служебных учётных записей. Wardrobe обращается к файловому API с паролем из `FILE_WARDROBE_PASSWORD_FILE`. Пользователю `APP_UID` нужен доступ на чтение этих файлов.
- `AI_VALIDATION_PASSWORD_FILE` — абсолютный путь к отдельному паролю для проверки входных данных задания. Его читают recommendation и wardrobe.
- `APP_UID` — результат `id -u`; `CHATGPT_CREDENTIALS_DIR` и `OPENAI_MODEL` — настройки [подключения ChatGPT](#ai-подбор).
- `GRAFANA_ADMIN_PASSWORD` — начальный пароль пользователя `admin` в Grafana. При существующем томе Grafana использует сохранённые учётные данные.
- `APP_JWT_PRIVATE_KEY_FILE` и `APP_JWT_PUBLIC_KEY_FILE` — абсолютные пути к ключам JWT, созданным ниже.
- `APP_BOOTSTRAP_ADMIN_LOGIN` и `APP_BOOTSTRAP_ADMIN_PASSWORD` — первый администратор; пароль от 12 до 128 символов. Значения с `$` в `.env` заключайте в одинарные кавычки. Действующий пароль при повторном запуске не перезаписывается; после настройки эти две переменные можно убрать.
- `AUTH_REFRESH_COOKIE_SECURE=false` — только для локального HTTP на `127.0.0.1`; при HTTPS установите `true`.
- `KAFKA_SECRETS_DIR` — абсолютный путь к каталогу сертификатов и credentials Kafka вне репозитория. Создайте его один раз командой ниже; при обновлении сохраните вместе с томами Kafka.

```shell
mkdir -p "$HOME/.config/clothes-advisor"
python3 infra/kafka/prepare.py --secrets-dir "$HOME/.config/clothes-advisor/kafka"
```

На существующем стенде повторите эту команду с прежним путём: она сохранит cluster ID, ключи брокеров и пользователей и создаст рядом каталог `kafka.monitor` для метрик. Укажите абсолютный путь к исходному каталогу **без завершающего слеша** в `KAFKA_SECRETS_DIR`. [Запуск Kafka и диагностика](docs/lab4/kafka.md)

Создайте пароли внутренних HTTP-клиентов и запишите пути к файлам в `.env`.

```shell
(
  umask 077
  cd "$HOME/.config/clothes-advisor" || exit 1
  test -e file-wardrobe-password || openssl rand -hex 32 > file-wardrobe-password
  test -e file-recommendation-password || openssl rand -hex 32 > file-recommendation-password
  test -e ai-validation-password || openssl rand -hex 32 > ai-validation-password
)
```

Создайте ключи JWT один раз вне репозитория.

```shell
mkdir -p "$HOME/.config/clothes-advisor"
chmod 700 "$HOME/.config/clothes-advisor"
test ! -e "$HOME/.config/clothes-advisor/jwt-private.pem" && \
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  -out "$HOME/.config/clothes-advisor/jwt-private.pem"
openssl pkey -in "$HOME/.config/clothes-advisor/jwt-private.pem" -pubout \
  -out "$HOME/.config/clothes-advisor/jwt-public.pem"
chmod 600 "$HOME/.config/clothes-advisor/jwt-private.pem"
chmod 644 "$HOME/.config/clothes-advisor/jwt-public.pem"
```

Закрытый ключ монтируется только в account. Gateway, account, wardrobe и notification читают открытый ключ. Пользователь `APP_UID` должен иметь право читать закрытый ключ.

Профиль `local` создаёт демонстрационные аккаунты `user`, `stylist`, `admin` без рабочих паролей. Bootstrap задаст пароль `admin`, если это выбранный первоначальный ADMIN; пароли остальных назначаются из интерфейса администратора. Регистрации нет, общего тестового пароля нет.

Account запускается с отдельным PostgreSQL и томом `account-postgres-data`; его HTTP и БД доступны только внутри Compose. Профиль `local` создаёт демонстрационные аккаунты только в account до готовности сервиса; Gateway ждёт его healthcheck.

На существующей установке проверьте версию Flyway до обновления. Для перехода с общей БД аккаунтов нужен перенос до V11. При переходе с ЛР3 выполните перенос метаданных фотографий на V22 до первого запуска wardrobe с V23. На новой пустой установке Flyway создаёт таблицы при старте. [Перенос аккаунтов](docs/lab2/account-data-transfer.md) · [Перенос фотографий](docs/lab4/file-transfer.md)

```shell
docker compose up --build -d
```

После старта Gateway проверьте `curl http://127.0.0.1:8080/actuator/health`: ожидаемый ответ — `{"status":"UP"}`. Регистрация в Eureka распространяется асинхронно; первые API-запросы могут вернуть 503 до обновления реестра. На `127.0.0.1` опубликованы Gateway (`APP_PORT`, по умолчанию 8080), Eureka (8761) и Grafana (`GRAFANA_PORT`, по умолчанию 3000). Базы данных и остальные приложения не публикуют порты. Для SQL-консоли используйте `docker compose exec postgres psql -U clothes_advisor -d clothes_advisor` (при стандартных DB_USER/DB_NAME). Ссылки ниже рассчитаны на порт 8080.

Внутренняя сеть `backend` связывает доверенные контейнеры приложений, у каждой БД своя изолированная сеть. Gateway и Eureka используют `edge`. Wardrobe обращается к file-service по внутренней сети. Через `egress` file-service работает с IAM и S3, а recommendation обращается к OpenAI. В Eureka публикуются DNS-имена Compose. Публичные API проверяют JWT и игнорируют `X-User-Id`.

Остановка — `docker compose down`. Данные PostgreSQL и Kafka сохраняются в именованных томах. `.env` с паролями и ключами не коммитится.

Миграции не исправляют старые данные автоматически. Если в существующей БД есть названия длиннее 300 символов или логины, цвет и материал длиннее 100, новые ограничения остановят запуск: такие записи нужно сначала проверить и исправить вручную.

File-service загружает и читает фотографии через HTTP API Yandex Object Storage с IAM-токеном. Укажите путь к JSON-файлу авторизованного ключа сервисного аккаунта в `YANDEX_SERVICE_ACCOUNT_KEY_FILE`. Compose монтирует этот ключ только в контейнер file-service, для чтения по пути `/run/secrets/yandex-service-account-key`. [Аутентификация Object Storage](https://yandex.cloud/ru/docs/storage/api-ref/authentication) · [Создание ключа](https://yandex.cloud/ru/docs/iam/operations/authorized-key/create)

File-service получает IAM-токен по подписанному JWT при первом обращении к Object Storage и перечитывает ключ при обновлении токена. Если IAM временно недоступен, сервис использует действующий токен и повторяет попытку через 30 секунд. Без действующего токена загрузка и скачивание возвращают 503. Список фотографий wardrobe читает из своей БД.

## Мониторинг

После подготовки Kafka и `docker compose up -d --build` войдите в Grafana под пользователем `admin`. Начните с обзора. Меню «Дашборды Clothes Advisor» сохраняет выбранный интервал при переходе к деталям.

| Дашборд | Что проверить |
| --- | --- |
| [Состояние сервисов](http://127.0.0.1:3000/d/ca-services) | Готовность девяти приложений, запросы/с, доля 5xx и p95 задержки. Если p95 вырос, найдите затронутый сервис. |
| [JVM](http://127.0.0.1:3000/d/ca-runtime) | CPU процесса, занятая и выделенная heap-память, non-heap, длительность и доля времени в паузах GC, состояния потоков. |
| [Подключения к PostgreSQL](http://127.0.0.1:3000/d/ca-database) | Сколько соединений занято, сколько ещё можно выдать и есть ли очередь. HikariCP используется в wardrobe, R2DBC — в account, file, notification и recommendation. Это клиентские пулы; посторонние сессии PostgreSQL в них не входят. |
| [Kafka](http://127.0.0.1:3000/d/ca-kafka) | Lag по группе и топику, самая отстающая партиция, участники групп, брокеры и неполная репликация. Если lag растёт, а участников нет, проверьте consumer. До первого commit отсутствие данных не означает нулевой lag. |
| [Логи и трассы](http://127.0.0.1:3000/d/ca-diagnostics) | Сколько записей каждого уровня поступило в Loki за период и как менялась их частота. Для фонового подбора введите `jobId` в поле «Текст в логах» и откройте `trace_id` у найденной записи. |

Обзор построен по [сигналам Google SRE](https://sre.google/sre-book/monitoring-distributed-systems/) и [рекомендациям Grafana по RED и USE](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/): сначала виден симптом, затем — нагрузка на конкретный ресурс. p95 рассчитывается по [гистограмме](https://prometheus.io/docs/practices/histograms/), а не по средним значениям сервисов. Без трафика p95 и доля ошибок не определены.

Трассы в локальном стенде по умолчанию записываются полностью. Доля новых трасс настраивается переменной `TRACE_SAMPLING_PROBABILITY` от 0 до 1.

Восемь Spring-приложений отправляют телеметрию по OTLP во внутренний `collector`. Grafana LGTM хранит логи в Loki, трассы в Tempo и метрики в Prometheus. Данные сохраняются в томе `grafana-data`. Журналы PostgreSQL, Kafka, Caddy и утилиты обновления токена доступны через `docker compose logs`. Это конфигурация локального учебного стенда. [Документация Grafana LGTM](https://grafana.com/docs/opentelemetry/docker-lgtm/)

## Веб-интерфейс

Откройте [Clothes Advisor](http://127.0.0.1:8080/) и войдите с логином и паролем. В интерфейсе доступны гардероб с фотографиями, образы и история оценок. Владелец выдаёт доступ стилистам и запускает AI-подбор; стилист работает с клиентами, создаёт образы, ставит и отменяет оценки; администратор меняет роли и статусы, просматривает и удаляет фотографии и образы пользователей.

React-приложение находится в `frontend/src`. Node.js и Vite нужны только при сборке; готовые файлы обслуживает отдельный контейнер Caddy. Адреса `/` и `/index.html` перенаправляют на `/app/`. Для независимой пересборки: `docker compose up -d --build --no-deps frontend`.

При локальном запуске backend через `bootRun` запустите frontend отдельно. Gateway по умолчанию ищет его на порту 8082:

```shell
docker build -t clothes-advisor-frontend ./frontend
docker run --rm -p 127.0.0.1:8082:8080 clothes-advisor-frontend
```

Войдите с паролем. ADMIN может создавать пользователей и назначать новые пароли в разделе «Аккаунты». Access token хранится в памяти вкладки, refresh — в HttpOnly-cookie. Перезагрузка восстанавливает вход; подтверждённый выход очищает данные страницы. AI-подбор доступен роли USER при настроенном подключении ChatGPT.

## API

Вызовите `POST /api/auth/login` с JSON-полями `login` и `password` и заголовком `X-Auth-Request: 1`. Полученный `accessToken` передавайте в Authorization. Для примера ниже нужен токен USER:

```shell
curl http://127.0.0.1:8080/api/wardrobe/items -H "Authorization: Bearer $ACCESS_TOKEN"
curl http://127.0.0.1:8080/api/wardrobe/items \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Рубашка","categoryId":1,"color":"Белый","material":"Хлопок"}'
```

Включите профиль `SPRING_PROFILES_ACTIVE=local` в `.env` и выполните `docker compose up -d`. В общем Swagger UI выберите спецификацию, выполните вход в Account и вставьте access token в **Authorize** выбранной спецификации. Запросы идут через текущий адрес Gateway. [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) · [Account](http://127.0.0.1:8080/v3/api-docs/account) · [Wardrobe](http://127.0.0.1:8080/v3/api-docs/wardrobe) · [Notification](http://127.0.0.1:8080/v3/api-docs/notification)

`POST /api/auth/refresh` обновляет токен по cookie; `POST /api/auth/logout` отзывает сессию. Оба требуют `X-Auth-Request: 1`. После выхода или смены пароля уже выданный access token действует до истечения срока; блокировка пользователя запрещает следующие бизнес-запросы сразу.

## AI-подбор

Локальная интеграция использует личную подписку ChatGPT и доступна роли USER. API-ключ и автоматический переход на платный API не используются. В настройках ChatGPT Usage отключите дополнительные кредиты для подключения.

Промпт находится в `config-repository/recommendation-service/application.yml`: `app.openai.prompt.text` — текст, `version` — положительное целое число. При правке меняйте оба поля и сохраняйте файл целиком. Recommendation подхватит изменения автоматически при очередном опросе, по умолчанию через 30 секунд; перезапуск и пересборка не нужны. Уже начатый подбор использует прежний снимок. Невалидная правка сохраняет последний рабочий промпт; если его ещё нет, AI возвращает `503`. Параметр `app.openai.prompt.poll-interval` меняется только при запуске.

В той же версии API повтор принятого запроса с тем же телом и `Idempotency-Key` возвращает сохранённый ответ. Для старого синхронного API это `201` с ресурсом, для v2 — `202` с тем же jobId. Если успешный ответ уже сохранён в другой версии, сервис вернёт `409`. Текущий статус задания v2 и ссылку на результат можно посмотреть по адресу из `Location` либо в «Мои AI-задания». [Контракты и ошибки AI](docs/lab4/api.md)

Вход выполняется отдельной локальной утилитой, которая не входит в этот репозиторий. Контейнер `chatgpt-token-refresher` использует созданные ею `registration.json` и `credentials.json`:

- `CHATGPT_CREDENTIALS_DIR` — абсолютный путь к приватному каталогу этих файлов вне репозитория. Права каталога — `700`, файлов — `600`; владелец соответствует `APP_UID`.
- `OPENAI_MODEL` — доступная аккаунту модель с поддержкой изображений и структурированного ответа.

Подключение включено в `compose.yaml`; команда запуска та же: `docker compose up --build -d`. Приватный каталог должен существовать до запуска и монтируется только в refresher, в `/run/chatgpt-private`, для чтения и записи. Refresher работает без root, с UID владельца файлов из `APP_UID`, без опубликованных портов. Он каждые 30 секунд проверяет срок и обновляет токен за 60 секунд до истечения, учитывая `earliest_refresh_at` провайдера. В отдельный том `chatgpt-access` записываются только access token и данные его привязки/срока; recommendation получает этот том в `/run/chatgpt` только для чтения и перечитывает токен при каждом запросе. Refresh token, ID token и промежуточный ответ остаются приватными. Wardrobe не получает credentials ChatGPT.

Если нужен отдельный PKCS12 для OpenAI, положите его в приватный каталог с правами `600` и задайте `CHATGPT_TRUST_STORE=/run/chatgpt/<имя>.p12`; пароль — `changeit`. Refresher проверит, что хранилище содержит только доверенные сертификаты, и скопирует его в выходной том до готовности recommendation. Хранилища с приватными ключами отклоняются. Иначе оставьте переменную пустой. Это доверие применяется к OAuth-клиенту refresher и клиенту OpenAI recommendation.

Для запуска без AI можно указать пустой каталог с правами `700` и оставить `OPENAI_MODEL` пустым. Готовность refresher подтверждает инициализацию каталога и TLS. В v2 принятие задания и вызов провайдера разделены во времени. Если внешний запрос мог выполниться, а результат потерян, задание получает `UNKNOWN`, личное подключение ставится на паузу. Оператор проверяет состояние слота перед возобновлением. [Восстановление после сбоя](docs/lab4/ai-execution-recovery.md)

Перед совместным использованием пересоберите локальную утилиту входа с поддержкой `command.guard`: старый файловый lock не синхронизирует macOS с bind mount Docker/Colima. Оба процесса создают этот каталог атомарно и не удаляют чужую блокировку. Обычная остановка освобождает её после завершения записи. После принудительного завершения сначала остановите refresher (`docker compose stop chatgpt-token-refresher`) и все процессы локальной утилиты, убедитесь, что писателей больше нет, затем удалите только пустой `command.guard` командой `rmdir` в приватном каталоге. Не удаляйте его при работающих процессах; автоматического перехвата блокировки нет.

Ответ ротации сохраняется в приватный `refresh-state.json` до запроса JWKS. При перезапуске проверка продолжается без повторного использования прежнего refresh token. После `429`/`5xx` или неоднозначной сетевой ошибки повтор ротации блокируется: токен мог быть уже заменён. Требуется новый вход тем же аккаунтом; он также нужен при отказе авторизации или несовпадении личности. Сбой получения JWKS повторяет только проверку сохранённого ответа. При потере ответа до сохранения бесшовное восстановление невозможно. Старые credentials сохраняют привязку личности для защиты повторного входа от смены аккаунта. Не удаляйте checkpoint вручную: новый успешный вход заменяет поколение credentials и снимает блокировку без перезапуска контейнеров.

## Сборка и тесты

Для каждого контроллера HTTP-контракт лежит в соседнем интерфейсе `*Api`: маршруты, параметры, валидация и аннотации OpenAPI. Класс `*Controller` реализует этот интерфейс и вызывает сервис. Пример — [AuthApi](account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/controller/auth/AuthApi.kt) и [AuthController](account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/controller/auth/AuthController.kt).

Для сборки нужен JDK 25, для интеграционных тестов — работающий Docker. Каждый backend-сервис имеет собственный `settings.gradle.kts` и собирается без Gradle-файлов остальных сервисов. Wrapper общий; команды выполняются из корня репозитория:

```shell
./gradlew -p wardrobe-service bootJar       # независимая сборка wardrobe
./gradlew -p account-service bootJar        # независимая сборка account
./gradlew -p recommendation-service bootJar # независимая сборка recommendation
./gradlew -p chatgpt-token-refresher test installDist # независимый refresher без Spring
./gradlew -p api-gateway bootJar            # аналогично для Gateway, Config и Eureka
./gradlew bootJar                 # JAR всех backend-сервисов через общую сборку
./gradlew test                    # unit- и интеграционные тесты
./gradlew check                   # проверки бизнес-сервисов
./gradlew :wardrobe-service:transportTest # максимальный пакет изображений, отдельная JVM с heap 512 МиБ
```

Dockerfile каждого сервиса копирует только wrapper, собственные файлы сборки и `src/main`. Корневая многомодульная сборка сохранена для общих команд и транспортного теста между wardrobe и recommendation; в Docker она не используется. Транспортный тест запускается только из корневой сборки. Интеграционные тесты используют конфигурации из соседнего `config-repository`, а тест переноса аккаунтов — также старые миграции wardrobe; поэтому тесты запускаются из полного checkout.

Для Docker-сборок нужен BuildKit/Buildx (`docker buildx version`). JVM-сервисы используют
общий кеш Gradle: изменение исходников не требует заново скачивать wrapper и зависимости.
Кеш заблокирован на время Gradle-шагов, поэтому они выполняются последовательно;
в runtime-образы кеш не попадает. Его очистка замедляет следующую сборку, но не меняет результат.

Полный стенд ЛР4 запускайте через Compose. При отдельном запуске через `bootRun` Gradle не читает `.env`: передайте сервису настройки его БД, Config Server, Eureka и пути к собственным secrets. Имена сервисов, Kafka credentials, внутренние HTTP-клиенты и настройки worker для контейнерного запуска заданы в [Compose](compose.yaml). Для сборки или проверки конкретного модуля укажите его имя, например `./gradlew :notification-service:test`.

При локальной отладке JWT задайте `JWT_PUBLIC_KEY_LOCATION=file:/абсолютный/путь/jwt-public.pem` для account, wardrobe, notification и Gateway. Закрытый ключ `JWT_PRIVATE_KEY_LOCATION` нужен account. Для локального HTTP используется `AUTH_REFRESH_COOKIE_SECURE=false`.

Сервисные тесты используют Mockito и PostgreSQL Testcontainers. Gateway проверяется HTTP-запросами, внешние клиенты — на локальных имитаторах. Frontend проверяется командой `npm test` в каталоге `frontend`.

В account, wardrobe, recommendation и chatgpt-token-refresher подключён JaCoCo. После `test` автоматически создаются отчёты: HTML — `<module>/build/reports/jacoco/test/html/index.html`, XML — `<module>/build/reports/jacoco/test/jacocoTestReport.xml`. Команда `./gradlew jacocoTestReport` запускает тесты и строит отчёты этих четырёх модулей. Обязательного порога покрытия нет.

Отдельный `:wardrobe-service:transportTest` создаёт отчёт `jacocoTransportTestReport` по wardrobe и recommendation. Он не запускается вместе с обычными `test` или `check`.

Для Colima на macOS перед тестами:

```shell
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
```

## Ограничения

- Погода задаётся вручную; погодного сервиса нет.
- Образы неизменяемы. Вещь из сохранённого образа удалить нельзя.
- Удаление фотографии убирает запись БД, но не файл S3. Автоочистки нет; при сбое загрузки могут остаться файлы без метаданных.
- При AI-подборе фотографии передаются OpenAI. Бакет остаётся приватным.
- Для публикации в интернет нужны HTTPS, ограничение частоты входа и отдельное согласование использования подключения ChatGPT. Basic Auth перед теми же API конфликтует с Bearer в Authorization. ЛР3 не обновляет удалённый стенд автоматически.

## Документация

- [ЛР4 по пунктам задания](docs/lab4/acceptance.md) · [Что изменилось после ЛР3](docs/lab4/changes.md)
- [Компоненты](docs/lab4/diagrams/components.svg) · [ER](docs/lab4/diagrams/data.svg) · [AI-подбор](docs/lab4/diagrams/ai-selection.svg) · [Запрос стилисту](docs/lab4/diagrams/stylist-request.svg)
- [Контракты HTTP](docs/lab4/api.md) · [События и топики](docs/lab4/messaging.md) · [Диагностика и отказы](docs/lab4/operations.md)
- [План и состояние ЛР3](docs/lab3/implementation.md) · [Авторизация: решения и теория](docs/lab3/theory.md)
- [Архитектура и схемы ЛР2](docs/lab2/architecture.md)
- [Контракты сервисов](docs/lab2/contracts.md) · [Итоговая проверка ЛР2](docs/lab2/acceptance.md)
- [Перенос аккаунтов существующей установки](docs/lab2/account-data-transfer.md)
- [Требования и сценарии ЛР1](docs/requirements.md)
- [Архитектура ЛР1](docs/architecture.svg) · [PlantUML](docs/architecture.puml)
- [ER-модель ЛР1](docs/domain.svg) · [PlantUML](docs/domain.puml)
