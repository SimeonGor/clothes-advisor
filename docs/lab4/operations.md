# Проверка доставки при отказах

Выполняйте из корня репозитория в Bash на **отдельном учебном стенде**. Нужны Docker Compose, jq, тестовые USER и STYLIST и синтетические данные. Используйте отдельный Docker context или свободные порты, собственные тома, каталог Kafka credentials и env-файл стенда. У Compose фиксированный loopback-порт discovery 8761. Для сценария AI подключите управляемый имитатор провайдера, который умеет задерживать ответ и считать обращения.

~~~bash
export LAB_PROJECT=ca-lab4-faults
export LAB_ENV=/absolute/private/lab4.env
export KAFKA_SECRETS_DIR=/absolute/private/lab4-kafka
dc() { docker compose --project-name "$LAB_PROJECT" --env-file "$LAB_ENV" -f compose.yaml "$@"; }
dc up -d --build
dc ps -a

# Используем локальный сокет в контейнере БД и настройки только этого сервиса.
db() {
  local service=$1
  shift
  dc exec -T "$service" sh -c 'exec psql -Xq -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"
}
q=ops/lab4-observability
~~~

Проверьте готовность приложений, миграции, три healthy-брокера и успешный exit kafka-provision. Для повторного запуска используйте тот же проект и каталог ключей. Запишите время, состояние очередей и lag перед внесением каждого сбоя и после восстановления.

## Очереди, ошибки и возраст

~~~bash
for service in account-postgres postgres recommendation-postgres; do
  echo "$service"
  db "$service" < "$q/outbox.sql"
done
db postgres < "$q/jobs.sql"
db recommendation-postgres < "$q/worker.sql"
db postgres < "$q/rejections.sql"
db recommendation-postgres < "$q/rejections.sql"
db notification-postgres < "$q/notifications.sql"
dc exec -T notification-service curl -fsS http://127.0.0.1:8080/actuator/metrics/notification.duplicates |
  jq '.measurements[] | select(.statistic == "COUNT") | .value'
~~~

SQL возвращает агрегаты по сохранённым строкам. Возраст измеряется в секундах, пустое поле возраста означает отсутствие соответствующей работы. При `statement_timeout` запишите ошибку измерения. Очередь с положительным `pending` и нулевым `due_now` может ждать истечения lease или паузы перед повтором. Сравните два снимка `oldest_pending_seconds`. `pending_retried` и `published_after_retry` считают события с несколькими захватами relay. Падение до отправки тоже увеличивает попытки.

`queued` в wardrobe включает время доставки до recommendation. `waiting` в recommendation считает принятые записи в состоянии RECEIVED, `waiting_past_deadline` — ещё не снятые просроченные записи. `oldest_active_seconds` отсчитывается от создания задания в wardrobe, `oldest_wait_seconds` — от исходного принятия задания. `slot_age_seconds` отсчитывается от захвата слота. Для долгого RUNNING проверьте также `phase`. `failed` и `unknown` — текущие количества по хранимым заданиям, `awaiting_application` — завершённые запросы без подтверждения от wardrobe. Retention может уменьшать эти значения.

Счётчик `notification.duplicates` растёт при попытке повторно сохранить пару `source_event_id, recipient_id`. Сравнивайте значения одного процесса между рестартами; для нескольких реплик собирайте каждую отдельно. Перезапуск обнуляет счётчик, уникальность пары сохраняется в БД.

## Lag и повторы публикации

Операторский CLI использует образ работающего брокера, внутреннюю сеть проекта и только клиентские права сервиса-потребителя. Сначала сохраните ID образа, пока все три брокера доступны.

~~~bash
broker_image=$(docker inspect -f '{{.Image}}' "$(dc ps -q kafka-1)")
bootstrap=kafka-1:9092,kafka-2:9092,kafka-3:9092
kc() {
  local principal=$1 tool=$2
  shift 2
  docker run --rm -i --network "${LAB_PROJECT}_kafka" --user 1000:1000 \
    --security-opt no-new-privileges --read-only --tmpfs /tmp \
    -e KAFKA_HEAP_OPTS='-Xms64m -Xmx256m' \
    --mount "type=bind,src=$KAFKA_SECRETS_DIR/$principal,dst=/run/client,readonly" \
    --entrypoint "/opt/kafka/bin/$tool.sh" "$broker_image" --bootstrap-server "$bootstrap" "$@"
}
lag() {
  kc "$1" kafka-consumer-groups --command-config /run/client/client.properties --describe --group "$2"
}
lag notification-service notification-service.events
lag recommendation-service recommendation-service.ai-selection
lag recommendation-service recommendation-service.ai-jobs
lag wardrobe-service wardrobe-service.ai-selection
~~~

Сохраняйте CURRENT-OFFSET, LOG-END-OFFSET и LAG по (group, topic, partition). Символ `-` или отсутствующая группа означает, что численного измерения нет. После первого commit offset команда показывает отставание и при остановленном consumer. Нулевой lag при пустой очереди брокера не исключает накопления событий в outbox. Назначения семи топиков приведены в [topology.tsv](../../infra/kafka/topology.tsv).

Чтобы измерить повторные публикации в учебной выборке, прочитайте **один непрерывный диапазон одной партиции**: одно чтение позиции соответствует одной сохранённой записи. Выберите `partition`, `first` и `n` по снимку lag и известному числу тестовых сообщений. Ниже пример для двух записей начиная с offset 0. Используйте партицию события и фактическое начало опыта. Значения JSON проходят только через pipe на локальный jq. Не включайте tee или shell tracing.

~~~bash
set -o pipefail
partition=0 first=0 n=2
kc notification-service kafka-console-consumer --consumer.config /run/client/client.properties \
  --topic stylist-access.events --partition "$partition" --offset "$first" \
  --max-messages "$n" --timeout-ms 10000 |
  jq -Rn --argjson expected "$n" '
    [inputs | fromjson? | .eventId? | strings |
      select(test("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")) | ascii_downcase] as $ids |
    if ($ids|length) == $expected then
      {records: ($ids|length), duplicate_publications: (($ids|length) - ($ids|unique|length))}
    else error("sample incomplete or invalid") end'
~~~

Эта выборка предназначена для однострочных JSON-конвертов, созданных relay. При ошибке чтения или неполной выборке результат не засчитывайте. Подсчёт одинаковых `eventId` на разных позициях отделяет повторную публикацию в брокер от повторной обработки того же offset. Результат относится только к выбранному диапазону.

## Корреляция AI

Из `202 JobAccepted` возьмите `jobId`. Это также `requestId`. Пара `requestMonth/requestId` образует ключ Kafka в AI-потоке. Каждое логическое событие получает свой `eventId`, повтор его публикации сохраняет ID. Таблицы запросов и outbox можно сопоставить вручную внутри соответствующих БД.

~~~bash
JOB=550e8400-e29b-41d4-a716-446655440000  # замените значением из ответа
db postgres -v job="$JOB" <<'SQL'
SELECT job_id, request_month, status FROM ai_job WHERE job_id=:'job'::uuid;
SELECT event_id, type, published_at FROM outbox_event WHERE aggregate_id=:'job'
  AND type IN ('RecommendationRequested','AiJobFinalized') ORDER BY created_at;
SQL
MONTH=2026-11-01  # возьмите request_month из предыдущего запроса
db recommendation-postgres -v job="$JOB" -v month="$MONTH" <<'SQL'
SELECT state, result_event_id, completed_at, applied_at, reviewed_at
FROM recommendation_request WHERE request_month=:'month'::date AND request_id=:'job'::uuid;
SELECT event_id, type, published_at FROM outbox_event
WHERE aggregate_id=:'job' AND payload->>'requestMonth'=:'month' ORDER BY created_at;
SQL
~~~

`AiJobFinalized.eventId` соответствует `notification.source_event_id`; для выбранного UUID запросите только `count(*)` из notification. Данные клиента, снимки входов, prompt, результаты AI, токены и JAAS-настройки в отчёт не включайте.

## Потеря брокеров и перезапуск уведомлений

В отдельном env задайте адрес Gateway с локальным портом проекта. Для команды ниже нужны ID тестового стилиста, действующий USER и закрытый файл (chmod 600) с одной строкой заголовка Authorization для этого USER. Пара изначально должна быть без выданного доступа. Выдача и отзыв дают по одному новому событию только при реальном изменении.

~~~bash
GATEWAY=http://127.0.0.1:8080
STYLIST=42
AUTH_HEADERS=/absolute/private/lab4-user.headers
grant() { curl -fsS -o /dev/null -w '%{http_code}\n' -X PUT -H @"$AUTH_HEADERS" "$GATEWAY/api/me/stylist-access/$STYLIST"; }
revoke() { curl -fsS -o /dev/null -w '%{http_code}\n' -X DELETE -H @"$AUTH_HEADERS" "$GATEWAY/api/me/stylist-access/$STYLIST"; }
~~~

1. Выполните `dc stop kafka-3`, затем `grant`. После выбора лидера ожидайте, что событие получит `published_at`, notification сохранит строку, lag вернётся к исходному значению. У кластера RF=3, min ISR=2. Выполните `dc start kafka-3` и дождитесь ISR из трёх реплик. Для проверки ISR оператор стенда выполняет внутри работающего узла `dc exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9094 --command-config /run/kafka-node/client.properties --describe --topic stylist-access.events`. В каждой из трёх партиций должны появиться все три ID в поле Isr.
2. Остановите `dc stop kafka-1 kafka-2 kafka-3`. Выполните `revoke`. Успешный HTTP-ответ и +1 pending в account-postgres подтверждают локальный commit. Снимите два значения oldest_pending_seconds с интервалом и зафиксируйте UUID тестового события (`SELECT event_id, type, attempts FROM outbox_event WHERE published_at IS NULL`). Во время полного отказа значения Kafka lag недоступны. Выполните `dc start kafka-1 kafka-2 kafka-3`. Дождитесь healthy, затем публикации этого UUID и одного уведомления. Время восстановления зависит от Kafka delivery timeout и срока lease relay.
3. При уже работающей группе выполните `dc stop notification-service` и `grant`. Убедитесь, что строка account outbox опубликована, LOG-END-OFFSET вырос, CURRENT-OFFSET отстаёт. Выполните `dc start notification-service`. Дождитесь commit offset и одной строки с `source_event_id` из этого outbox. Обычный рестарт `dc restart notification-service` сохраняет строку и позицию группы.

При проверке полного отказа на загруженной VM один узел завершился по таймауту регистрации в контроллере. Если кворум уже избрал лидера, проверьте `dc ps -a` и журнал завершившегося узла. В этом прогоне помог повторный `dc start kafka-1` с прежним томом и тем же cluster ID. После восстановления ISR проверьте исходный event ID в outbox и notification. Повторная выдача или отзыв доступа для доставки уже сохранённого события не требуются.

### Недоступность БД consumer

Начните с выданного доступа. В одном терминале запомните lag и число уведомлений, затем выполните `dc pause notification-postgres` и `revoke`. Из другого терминала вызовите `lag notification-service notification-service.events`. Пока БД остановлена, запись опубликована, а offset затронутой партиции не должен пройти её до фиксации локального эффекта. Не отправляйте SQL в замороженную БД. Выполните `dc unpause notification-postgres`. Дождитесь сохранения уведомления и commit offset. Если процесс listener остановился, проверьте readiness, затем `dc restart notification-service`. Повторная обработка исходного offset защищена уникальным ключом.

### Повтор того же события

В изолированном опыте выберите `event_id` одного **уже опубликованного** синтетического AccessGranted/AccessRevoked, для которого найдено ровно одно уведомление. Снимите значение `notification.duplicates` до и после. Разрешите relay опубликовать ровно эту сохранённую строку снова. Подставьте UUID из account outbox и проверьте, что RETURNING вернул одну строку.

~~~bash
EVENT=550e8400-e29b-41d4-a716-446655440001
db account-postgres -v event="$EVENT" <<'SQL'
UPDATE outbox_event SET published_at=NULL, next_attempt_at=clock_timestamp()
WHERE event_id=:'event'::uuid AND published_at IS NOT NULL AND type IN ('AccessGranted','AccessRevoked')
RETURNING event_id, type;
SQL
db notification-postgres -v event="$EVENT" <<'SQL'
SELECT count(*) FROM notification WHERE source_event_id=:'event'::uuid;
SQL
~~~

Дождитесь следующего commit группы. Число уведомлений для этого события остаётся 1, число подавленных доставок увеличивается на 1 на обработавшей реплике, в той же партиции появляется новая позиция. Проверяйте эти приращения при отсутствии другого трафика. Для нового опыта берите собственную пару пользователей.

## Некорректный конверт и восстановление

На учебном стенде отправьте синтетическую запись с неизвестной версией в топик, которым владеет account-service. Не используйте клиентские данные из журналов. У команды producer отдельные credentials издателя.

~~~bash
printf '%s\n' '{"schemaVersion":999}' |
  kc account-service kafka-console-producer --producer.config /run/client/client.properties --topic stylist-access.events
db notification-postgres < "$q/notifications.sql"
~~~

Дождитесь появления `UNSUPPORTED_VERSION` и commit offset. Следующая корректная запись должна быть обработана. Для записи той же партиции проверьте, что CURRENT-OFFSET группы выше offset отказа. При повторном чтении прежней позиции PK оставляет одну диагностическую строку. Другие consumers хранят причины в `kafka_delivery_rejection`. При разборе выводите `topic, partition_id, record_offset, reason`. Пауза или ошибка БД откладывает подтверждение до долговечной записи отказа. Постоянная ошибка конверта завершается диагностикой после проверки формата. Публичных DLT-топиков в этой топологии нет.

При исправлении несовместимого consumer сначала восстановите поддержку исходной схемы. Переотправка из известной корректной строки outbox сохраняет `eventId`, ключ, исходное время и payload. Сопоставьте строку с отказом до повтора; если происхождение не подтверждено, оставьте отказ для ручного разбора. Не собирайте новое сообщение из текста ошибки и не назначайте новый jobId. Массовая перемотка группы затрагивает все последующие записи партиции.

## Перезапуск worker и UNKNOWN

На имитаторе приостановите ответ на вызов. Отправьте тестовый запрос из [контракта AI](api.md) с валидными вещами и `Idempotency-Key`. Запишите jobId, количество вызовов имитатора и execution_id слота из `ai_execution_slot`. Убедитесь, что слот принадлежит этому request и находится в CALL_STARTED. Завершите все экземпляры исполнителя данного проекта, включая реплики, и проверьте состояние контейнеров.

~~~bash
dc kill -s SIGKILL recommendation-service
dc ps -a recommendation-service
db recommendation-postgres < "$q/worker.sql"
~~~

Завершите разбор прежнего execution_id по [инструкции восстановления слота](ai-execution-recovery.md). Для RUNNING/CALL_STARTED `recover_ai_execution` фиксирует UNKNOWN и PAUSED. После подтверждённого разбора `resume_ai_execution` разрешает будущие задания. Для остановленного PREPARING та же fenced-функция возвращает работу в очередь с исходным deadline. Перезапустите `dc start recommendation-service`, проверьте доставку результата и подтверждение применения, окончательное состояние wardrobe и число обращений имитатора. Повтор исходного запроса или результата для UNKNOWN не должен увеличить счётчик вызовов для этой работы. Только наблюдение имитатора позволяет измерить внешние обращения.

## Ресурсы и границы доступа

~~~bash
docker stats --no-stream $(dc ps -q)
dc config --services
~~~

Снимайте CPU и память под нагрузкой и во время накопления, отмечайте OOMKilled и перезапуски конкретного контейнера. Успешный единичный снимок не измеряет пиковую память. Проверяйте вход в закрытые HTTP-маршруты с синтетическим клиентом без авторизации, отсутствие публикации Kafka и БД на хост (`dc ps`), и отдельные ACL по [инструкции к кластеру](kafka.md). Для проверки WebFlux параллельно с задержанным внешним вызовом измерьте время независимых запросов и при необходимости снимите thread dump. Факт обработки Kafka на listener-thread сам по себе не подтверждает отзывчивость HTTP event loop.

В итоговый протокол внесите время нарушения и восстановления, имя сервиса и группы, topic/partition/offset, тестовый eventId/jobId, дельты метрик и результат запроса после восстановления. Помечайте сценарий непроверенным, если требуемое состояние или эффект не удалось наблюдать.
