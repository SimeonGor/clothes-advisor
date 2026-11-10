# Что изменилось в ЛР4

## Работа пользователя

В ЛР3 браузер ждал, пока recommendation подберёт вещи. Теперь USER может запустить подбор образа, замену или создание капсулы и вернуться к работе. После `202 Accepted` браузер получает UUID задания и URL для просмотра. В «Мои AI-задания» видны ход выполнения, ошибки и ссылка на готовый ресурс. Старые клиенты продолжают пользоваться синхронными маршрутами. [Контракты обеих версий](api.md) · [Интерфейс заданий](../../frontend/src/ai-jobs.tsx)

Пользователи и стилисты получают сообщения о выдаче и отзыве доступа, запросах, ответах, образах и оценках. USER также видит окончательный исход AI. В ленте можно открыть ресурс и отметить сообщение прочитанным. Права на сам ресурс проверяются при переходе. [Лента](../../frontend/src/notifications.tsx) · [События и адресаты](messaging.md)

## Границы сервисов

| Где | Как разделена ответственность |
| --- | --- |
| Account | При выдаче и отзыве доступа меняет разрешение и добавляет событие в одной транзакции своей БД. Relay доставляет зафиксированный outbox в Kafka. [Сценарий доступа](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/access/AccessGrantService.kt) · [Relay](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/messaging/AccessOutboxRelay.kt) |
| Wardrobe | Владеет предметным результатом. Принимает команду, проверяет доступ и вход, присваивает jobId. При результате повторно проверяет исходные данные, записывает образ или капсулу, исход задания и событие завершения. [Приём задания](../../wardrobe-service/src/main/kotlin/ru/itmo/clothesadvisor/service/ai/AiJobService.kt) · [Применение результата](../../wardrobe-service/src/main/kotlin/ru/itmo/clothesadvisor/service/ai/AiJobResults.kt) |
| Recommendation | Входящий consumer сохраняет запрос. Worker захватывает общий слот, читает фото, проверяет вход, вызывает AI и фиксирует исход. Доставку результата выполняет relay. Сеть и AI вынесены за пределы транзакции с PostgreSQL. [Consumer](../../recommendation-service/src/main/kotlin/ru/itmo/clothesadvisor/recommendationservice/messaging/RecommendationRequestListener.kt) · [Worker](../../recommendation-service/src/main/kotlin/ru/itmo/clothesadvisor/recommendationservice/execution/ExecutionWorker.kt) |
| File | Служебный HTTP обрабатывает байты и метаданные. Сценарий загрузки сверяет формат, сервис сохраняет объект и привязывает ключ в своей БД. Владелец, роль стилиста и лимит на вещь относятся к wardrobe. [Файловый сценарий](../../file-service/src/main/kotlin/ru/itmo/clothesadvisor/fileservice/service/FileService.kt) · [Проверка изображения](../../file-service/src/main/kotlin/ru/itmo/clothesadvisor/fileservice/service/PhotoUploadService.kt) |
| Notification | Получает события пяти топиков. Правила выбора получателя и ссылки отделены от HTTP, обработки offset и хранения. PostgreSQL обеспечивает уникальность уведомления и хранит время прочтения. [Адресаты событий](../../notification-service/src/main/kotlin/ru/itmo/clothesadvisor/notification/DomainEvents.kt) · [Хранилище](../../notification-service/src/main/kotlin/ru/itmo/clothesadvisor/notification/PostgresNotifications.kt) |
| Инфраструктура | Gateway проверяет JWT и маршрутизирует, Config Server отдаёт конфигурацию, Eureka хранит реестр. Refresher обновляет локальное подключение и выдаёт recommendation access token через том только для чтения. [Маршруты](../../config-repository/api-gateway/application.yml) · [Контейнеры и тома](../../compose.yaml) |

При архитектурном ревью SQL и блокировки в wardrobe вынесены в `AiJobRepository`, а разбор Kafka-записи — в listener. Сценарий `AiJobResults` теперь получает разобранное событие. В recommendation запись и повторное применение состояния сосредоточены в `RecommendationRequestStore`. У сервисов сохраняются технические зависимости от Spring, Reactor и сериализации. Например, `DomainEvents` разбирает JSON. [Результаты ревью по сервисам](architecture-review.md) · [Слои](diagrams/layers.svg) · [Принцип границ в Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)

## Доставка и восстановление

Пока брокер недоступен, принятая команда остаётся в таблице outbox. Relay вправе повторить публикацию, например после остановки между отправкой и отметкой `published_at`. Получатель подтверждает Kafka только после локальной фиксации. Разные эффекты имеют разные ключи. Для запроса recommendation это месяц UTC и UUID, для уведомления — событие и адресат. Для HTTP-команды wardrobe продолжает действовать ключ пользователя и операции. [Схема данных](diagrams/data.svg) · [Доставка Kafka](https://kafka.apache.org/41/design/design/#message-delivery-semantics)

Один постоянный слот координирует синхронные и фоновые обращения к личному подключению. Если исполнитель остановился до вызова, оператор может вернуть работу в очередь с прежним deadline. После возможной передачи запроса провайдеру результат остаётся `UNKNOWN`, слот — `PAUSED`. Работу разбирают по `execution_id`. Следующий подбор получает свой UUID. [Восстановление](ai-execution-recovery.md)

Месячные партиции принадлежат recommendation. Старый период закрывается после завершения работы, подтверждения результата, отправки outbox и разбора `UNKNOWN`. Барьер закрытия фиксируется до отсоединения и удаления партиции. Для Kafka настроен собственный срок хранения записей. [Политика PostgreSQL](data.md) · [Политика Kafka](kafka.md)

## Что показать на схеме

- По компонентам проследите путь браузера через Gateway и разделение данных между сервисами. [Компоненты](diagrams/components.svg)
- На ER указаны ключевые столбцы для объяснения повторов. Пунктир соединяет данные логически. Между БД сервисов нет внешних ключей. Уведомление получает исходное событие от одного издателя. [ER](diagrams/data.svg)
- Отдельные последовательности показывают, когда можно вернуть `202`, когда появляется готовый ресурс и кто получает ответ стилиста. [AI-задание](diagrams/ai-selection.svg) · [Запрос стилисту](diagrams/stylist-request.svg)
- По топикам проследите два независимых чтения `ai-jobs.events`. Recommendation получает подтверждение применения, notification создаёт сообщение для пользователя. [Топики и потребители](diagrams/topics.svg)
