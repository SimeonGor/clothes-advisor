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

В ЛР4 `file-service` регистрируется в Eureka, проверяет JPEG и PNG до загрузки и читает фотографии из Yandex Object Storage через IAM. Старт и health работают без обращения к облаку. Пользовательские маршруты фотографий пока обслуживает wardrobe. Его переключение следует за подключением файлового API и БД.

## Быстрый запуск

Нужны Docker с Compose 2.20.3+, Python 3 и OpenSSL для первичной подготовки Kafka, а также приватный бакет Yandex Object Storage с доступом на чтение и загрузку файлов.

Если `.env` ещё нет, создайте его из примера:

```shell
cp .env.example .env
```

Заполните в `.env`:

- `S3_BUCKET` — имя приватного бакета.
- `YANDEX_SERVICE_ACCOUNT_KEY_FILE` — абсолютный путь к JSON-файлу авторизованного ключа сервисного аккаунта с правами чтения и загрузки объектов в этот бакет. Храните файл вне репозитория и контекста сборки Docker; он должен быть доступен для чтения пользователю `APP_UID`.
- `DB_PASSWORD` — пароль PostgreSQL гардероба.
- `ACCOUNT_DB_PASSWORD` — отдельный пароль PostgreSQL аккаунтов. Оба значения из примера подходят только для локального запуска.
- `APP_UID` — результат `id -u`; `CHATGPT_CREDENTIALS_DIR` и `OPENAI_MODEL` — настройки [подключения ChatGPT](#ai-подбор).
- `APP_JWT_PRIVATE_KEY_FILE` и `APP_JWT_PUBLIC_KEY_FILE` — абсолютные пути к ключам JWT, созданным ниже.
- `APP_BOOTSTRAP_ADMIN_LOGIN` и `APP_BOOTSTRAP_ADMIN_PASSWORD` — первый администратор; пароль от 12 до 128 символов. Значения с `$` в `.env` заключайте в одинарные кавычки. Действующий пароль при повторном запуске не перезаписывается; после настройки эти две переменные можно убрать.
- `AUTH_REFRESH_COOKIE_SECURE=false` — только для локального HTTP на `127.0.0.1`; при HTTPS установите `true`.
- `KAFKA_SECRETS_DIR` — абсолютный путь к каталогу сертификатов и credentials Kafka вне репозитория. Создайте его один раз командой ниже; при обновлении сохраните вместе с томами Kafka.

```shell
mkdir -p "$HOME/.config/clothes-advisor"
python3 infra/kafka/prepare.py --secrets-dir "$HOME/.config/clothes-advisor/kafka"
```

Родительский каталог должен существовать. Повторная команда проверяет имеющиеся файлы и не меняет пароли или cluster ID. Добавьте полученный абсолютный путь в `.env`. Kafka включена в общий Compose: три брокера, семь топиков, TLS/SCRAM и права по сервисам. Подключение бизнес-сервисов к очередям относится к следующим этапам ЛР4. [Запуск Kafka отдельно и диагностика](docs/lab4/kafka.md).

Создайте ключи один раз вне репозитория. Повторно выполнять генерацию при обновлении приложения не нужно:

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

Закрытый ключ монтируется только в account, открытый — также в wardrobe и Gateway. В образах ключей нет. Пользователь `APP_UID` должен иметь право читать закрытый ключ.

Профиль `local` создаёт демонстрационные аккаунты `user`, `stylist`, `admin` без рабочих паролей. Bootstrap задаст пароль `admin`, если это выбранный первоначальный ADMIN; пароли остальных назначаются из интерфейса администратора. Регистрации нет, общего тестового пароля нет.

Account запускается с отдельным PostgreSQL и томом `account-postgres-data`; его HTTP и БД доступны только внутри Compose. Профиль `local` создаёт демонстрационные аккаунты только в account до готовности сервиса; Gateway ждёт его healthcheck.

При обновлении существующей установки сначала остановите запись и выполните [перенос аккаунтов и сверку манифестов](docs/lab2/account-data-transfer.md). Только после проверки запускайте новую wardrobe: Flyway V11 автоматически снимает пять внешних ключей на старую копию аккаунтов. Не запускайте новый образ wardrobe до переноса. Старые таблицы и история сохраняются; после новых записей возвращать старого писателя без обратной сверки нельзя.

```shell
docker compose up --build -d
```

После старта Gateway проверьте `curl http://127.0.0.1:8080/actuator/health`: ожидаемый ответ — `{"status":"UP"}`. Регистрация в Eureka распространяется асинхронно; первые API-запросы могут вернуть 503 до обновления реестра. На хост публикуются только Gateway (`APP_PORT`, по умолчанию 8080) и Eureka (8761), оба на `127.0.0.1`. Базы данных и остальные приложения не публикуют порты. Для SQL-консоли используйте `docker compose exec postgres psql -U clothes_advisor -d clothes_advisor` (при стандартных DB_USER/DB_NAME). Ссылки ниже рассчитаны на порт 8080.

Внутренняя сеть `backend` связывает приложения; у каждой БД своя изолированная сеть. `edge` используется Gateway/Eureka, `egress` даёт wardrobe/recommendation выход к S3/IAM/OpenAI. В Eureka публикуются DNS-имена Compose. Внутренние account/recommendation API не имеют маршрутов Gateway; эта сетевая граница не защищает от компрометации доверенного контейнера. Публичные API проверяют JWT; `X-User-Id` игнорируется.

Остановка — `docker compose down`. Данные PostgreSQL и Kafka сохраняются в именованных томах. `.env` с паролями и ключами не коммитится.

Миграции не исправляют старые данные автоматически. Если в существующей БД есть названия длиннее 300 символов или логины, цвет и материал длиннее 100, новые ограничения остановят запуск: такие записи нужно сначала проверить и исправить вручную.

Фотографии передаются в [HTTP API Yandex Object Storage](https://yandex.cloud/ru/docs/storage/api-ref/authentication) с IAM-токеном, без AWS SDK. Создайте [авторизованный ключ сервисного аккаунта](https://yandex.cloud/ru/docs/iam/operations/authorized-key/create) и укажите путь к JSON-файлу в `YANDEX_SERVICE_ACCOUNT_KEY_FILE`. Compose монтирует его как секрет только для чтения в `/run/secrets/yandex-service-account-key`; в образ ключ не попадает.

Приложение получает IAM-токен по подписанному JWT при первом обращении к Object Storage и обновляет его автоматически до истечения срока. При временном сбое IAM используется только ещё действующий токен; повторная попытка — через 30 секунд. Если действующий токен получить не удалось, загрузка фотографий и получение их содержимого возвращают 503. Работа с метаданными фотографий токена не требует. Запуск приложения не обращается к IAM; ключ перечитывается при каждом обновлении токена.

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

Общий [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) предлагает спецификации **Account** и **Wardrobe**. Выполните вход в Account, затем вставьте access token в **Authorize** выбранной спецификации. JSON доступен отдельно для [Account](http://127.0.0.1:8080/v3/api-docs/account) и [Wardrobe](http://127.0.0.1:8080/v3/api-docs/wardrobe).

OpenAPI и Swagger UI выключены по умолчанию в Account, Wardrobe и Gateway. Для включения задайте `SPRING_PROFILES_ACTIVE=local` в `.env` и выполните `docker compose up -d`: Compose передаст профиль всем трём сервисам. При запуске через `bootRun` задайте эту переменную каждому из них. Запросы из UI идут через текущий адрес Gateway, включая выбранный порт.

`POST /api/auth/refresh` обновляет токен по cookie; `POST /api/auth/logout` отзывает сессию. Оба требуют `X-Auth-Request: 1`. После выхода или смены пароля уже выданный access token действует до истечения срока; блокировка пользователя запрещает следующие бизнес-запросы сразу.

## AI-подбор

Локальная интеграция использует личную подписку ChatGPT и доступна роли USER. API-ключ и автоматический переход на платный API не используются. В настройках ChatGPT Usage отключите дополнительные кредиты для подключения.

Промпт находится в `config-repository/recommendation-service/application.yml`: `app.openai.prompt.text` — текст, `version` — положительное целое число. При правке меняйте оба поля и сохраняйте файл целиком. Recommendation подхватит изменения автоматически при очередном опросе, по умолчанию через 30 секунд; перезапуск и пересборка не нужны. Уже начатый подбор использует прежний снимок. Невалидная правка сохраняет последний рабочий промпт; если его ещё нет, AI возвращает `503`. Параметр `app.openai.prompt.poll-interval` меняется только при запуске.

Повтор успешного запроса с тем же `Idempotency-Key` вернёт прежний образ даже после смены промпта. Для нового подбора нужен новый ключ. Генерация изображений не подключена: AI по-прежнему выбирает вещи из переданных кандидатов.

Вход выполняется отдельной локальной утилитой, которая не входит в этот репозиторий. Контейнер `chatgpt-token-refresher` использует созданные ею `registration.json` и `credentials.json`:

- `CHATGPT_CREDENTIALS_DIR` — абсолютный путь к приватному каталогу этих файлов вне репозитория. Права каталога — `700`, файлов — `600`; владелец соответствует `APP_UID`.
- `OPENAI_MODEL` — доступная аккаунту модель с поддержкой изображений и структурированного ответа.

Подключение включено в `compose.yaml`; команда запуска та же: `docker compose up --build -d`. Приватный каталог должен существовать до запуска и монтируется только в refresher, в `/run/chatgpt-private`, для чтения и записи. Refresher работает без root, с UID владельца файлов из `APP_UID`, без опубликованных портов. Он каждые 30 секунд проверяет срок и обновляет токен за 60 секунд до истечения, учитывая `earliest_refresh_at` провайдера. В отдельный том `chatgpt-access` записываются только access token и данные его привязки/срока; recommendation получает этот том в `/run/chatgpt` только для чтения и перечитывает токен при каждом запросе. Refresh token, ID token и промежуточный ответ остаются приватными. Wardrobe не получает credentials ChatGPT.

Если нужен отдельный PKCS12 для OpenAI, положите его в приватный каталог с правами `600` и задайте `CHATGPT_TRUST_STORE=/run/chatgpt/<имя>.p12`; пароль — `changeit`. Refresher проверит, что хранилище содержит только доверенные сертификаты, и скопирует его в выходной том до готовности recommendation. Хранилища с приватными ключами отклоняются. Иначе оставьте переменную пустой. Это доверие применяется к OAuth-клиенту refresher и клиенту OpenAI recommendation.

Без действующего подключения или при недоступном recommendation AI возвращает 503; остальные функции работают. Проверка данных и чтение фотографий выполняются до обращения к recommendation; при ошибке образ не сохраняется. Для запуска без AI можно указать пустой каталог с правами `700` и оставить `OPENAI_MODEL` пустым. Готовность refresher означает инициализацию каталога и TLS, а не успешный вход. OAuth-токены не храните в `.env` или Git.

Перед совместным использованием пересоберите локальную утилиту входа с поддержкой `command.guard`: старый файловый lock не синхронизирует macOS с bind mount Docker/Colima. Оба процесса создают этот каталог атомарно и не удаляют чужую блокировку. Обычная остановка освобождает её после завершения записи. После принудительного завершения сначала остановите refresher (`docker compose stop chatgpt-token-refresher`) и все процессы локальной утилиты, убедитесь, что писателей больше нет, затем удалите только пустой `command.guard` командой `rmdir` в приватном каталоге. Не удаляйте его при работающих процессах; автоматического перехвата блокировки нет.

Ответ ротации сохраняется в приватный `refresh-state.json` до запроса JWKS. При перезапуске проверка продолжается без повторного использования прежнего refresh token. После `429`/`5xx` или неоднозначной сетевой ошибки повтор ротации блокируется: токен мог быть уже заменён. Требуется новый вход тем же аккаунтом; он также нужен при отказе авторизации или несовпадении личности. Сбой получения JWKS повторяет только проверку сохранённого ответа. При потере ответа до сохранения бесшовное восстановление невозможно. Старые credentials сохраняют привязку личности для защиты повторного входа от смены аккаунта. Не удаляйте checkpoint вручную: новый успешный вход заменяет поколение credentials и снимает блокировку без перезапуска контейнеров.

## Сборка и тесты

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

Код гардероба находится в `wardrobe-service`, аккаунтов — в `account-service`, AI — в `recommendation-service`, Config Server — в `config-server`, Eureka — в `discovery-server`, единая HTTP-точка входа — в `api-gateway`. Для запуска без Docker нужны PostgreSQL и переменные окружения из `.env`: Gradle сам этот файл не читает. Сначала выполните `./gradlew :config-server:bootRun`, затем `./gradlew :discovery-server:bootRun`, `APP_PORT=8081 ./gradlew :wardrobe-service:bootRun`, `APP_PORT=8083 ./gradlew :recommendation-service:bootRun` и `./gradlew :api-gateway:bootRun`. Сервисы регистрируются в Eureka; обнаружение после запуска может занять некоторое время. Команды запускаются из корня репозитория; конкретный модуль можно указать явно: `./gradlew :wardrobe-service:test`.

Account также подключается к Config/Eureka и нужен wardrobe для запросов с пользователем: `APP_PORT=8084 ./gradlew :account-service:bootRun`. Ему нужна отдельная БД `account_db`: `DB_R2DBC_URL` — R2DBC-адрес, `DB_URL` — JDBC-адрес той же БД для Flyway, `DB_USER` и `DB_PASSWORD` — её учётные данные. При отдельном запуске account передайте в `DB_PASSWORD` значение `ACCOUNT_DB_PASSWORD`; Compose делает это автоматически. Не направляйте его на рабочую БД wardrobe.

При запуске JVM на хосте вместо Compose mounts задайте `JWT_PUBLIC_KEY_LOCATION=file:/абсолютный/путь/jwt-public.pem` для account, wardrobe и Gateway; `JWT_PRIVATE_KEY_LOCATION=file:/абсолютный/путь/jwt-private.pem` — только для account. Для account также нужны настройки первоначального ADMIN и `AUTH_REFRESH_COOKIE_SECURE=false`, если используется локальный HTTP.

Тесты бизнес-сервисов используют Mockito и PostgreSQL Testcontainers; подготовка и проверка данных выполняется через JDBC. Gateway имеет отдельные HTTP-тесты безопасности. HTTP-клиенты проверяются на локальных заглушках; секреты из `.env` и реальные вызовы S3/OpenAI не нужны. Frontend проверяется командой `npm test` в каталоге `frontend`.

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

- [План и состояние ЛР3](docs/lab3/implementation.md) · [Авторизация: решения и теория](docs/lab3/theory.md)
- [Архитектура и схемы ЛР2](docs/lab2/architecture.md)
- [Контракты сервисов](docs/lab2/contracts.md) · [Итоговая проверка ЛР2](docs/lab2/acceptance.md)
- [Перенос аккаунтов существующей установки](docs/lab2/account-data-transfer.md)
- [Требования и сценарии ЛР1](docs/requirements.md)
- [Архитектура ЛР1](docs/architecture.svg) · [PlantUML](docs/architecture.puml)
- [ER-модель ЛР1](docs/domain.svg) · [PlantUML](docs/domain.puml)
