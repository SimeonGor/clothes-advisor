# Проверка ЛР2

Проверено 22 октября 2026 года на коде `c1dbb6a` — после объединения Swagger. Завершающий этап меняет только документацию и диаграмму; исходники, зависимости, конфигурация и образы после проверки не менялись.

## Соответствие заданию

| Требование [ЛР2](https://github.com/Discipliny/highload_systems#лабораторная-работа-2) | Реализация | Проверка |
|---|---|---|
| Декомпозиция монолита | Account, wardrobe, recommendation; отдельные БД и frontend | Семь образов независимо собраны из исходников своих модулей; девять контейнеров Compose запущены |
| Eureka | Регистрация трёх бизнес-сервисов, обнаружение клиентов и Gateway | Запросы через Gateway и Feign после регистрации; восстановление discovery в тестах клиентов |
| Config Server | Общая несекретная конфигурация | `ConfigServerTests`, тесты конфигурации клиентов и запуск готовых образов |
| Spring Gateway | Единый адрес UI, API и Swagger | `ApiGatewayTests`; реальные GET Account/Wardrobe из Swagger, внутренние пути дают 404 |
| Feign | Wardrobe → account/recommendation | `AccountClientTests`, `RecommendationClientTests`; сквозные операции на Compose |
| Circuit Breaker | Resilience4j в обоих клиентах, без разрешающего fallback | Открытие/восстановление breaker, таймауты и обработка ответов в тестах клиентов |
| Reactor + R2DBC | Account | HTTP-интеграционные тесты с PostgreSQL и проверка реального R2DBC-сигнала |
| Reactor + Spring Data JDBC | Wardrobe | WebFlux, JDBC на worker-потоке; интеграционные проверки транзакций, multipart и внешних вызовов |

Production-зависимостей на JAR другого бизнес-сервиса нет. Общие Gradle-файлы нужны для конфигурации сборки. Агрегация JaCoCo и использование recommendation в отдельном `transportTest` — только проверочная инфраструктура.

## Сборка и тесты

```shell
./gradlew clean check bootJar :wardrobe-service:transportTest \
  --continue --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process
```

Чистый прогон: **523 теста и 1 отдельный транспортный тест**, без ошибок и пропусков. Выполнены все 59 задач. Суммарное покрытие строк — **95,93%** (2192 из 2285); проверяемый в сборке порог — 70%.

| Модуль | Тестов |
|---|---:|
| wardrobe-service | 343 |
| account-service | 138 |
| recommendation-service | 28 |
| api-gateway | 9 |
| config-server | 4 |
| discovery-server | 1 |

Результаты JUnit находятся в `<module>/build/test-results`, общий отчёт JaCoCo — в `build/reports/jacoco/testCodeCoverageReport`. Сервисы проверяются с Mockito, SQL и транзакции — на PostgreSQL Testcontainers. Дополнительные дублирующие тесты в документальном этапе не добавлялись.

Основные регрессионные сценарии:

- [AccountClientTests](../../wardrobe-service/src/test/kotlin/ru/itmo/clothesadvisor/client/account/AccountClientTests.kt) и соседние timeout-тесты: недоступность, некорректный ответ, отказ без fallback, восстановление.
- [WardrobeItemPhotoIntegrationTests](../../wardrobe-service/src/test/kotlin/ru/itmo/clothesadvisor/controller/wardrobe/WardrobeItemPhotoIntegrationTests.kt): лимит до S3 и под блокировкой, конкурентные загрузки, удаление фото во время скачивания, rollback метаданных.
- [OutfitRatingIntegrationTests](../../wardrobe-service/src/test/kotlin/ru/itmo/clothesadvisor/controller/outfit/OutfitRatingIntegrationTests.kt): версии и история, гонки оценки/удаления, отзыв доступа во время ожидания блокировки.
- [AiOutfitIntegrationTests](../../wardrobe-service/src/test/kotlin/ru/itmo/clothesadvisor/controller/ai/AiOutfitIntegrationTests.kt) и [AiOutfitServiceTests](../../wardrobe-service/src/test/kotlin/ru/itmo/clothesadvisor/service/ai/AiOutfitServiceTests.kt): занятый слот до подготовки фото, отмена HTTP, повторные проверки пользователя, удаление кандидатов, полный rollback.
- [AccountDataTransferTests](../../account-service/src/test/kotlin/ru/itmo/clothesadvisor/accountservice/migration/AccountDataTransferTests.kt): сохранение строк и sequence, отказ непустой цели, ошибки переноса, сверка манифестов и снятие ровно пяти FK.
- [RecommendationPayloadTests](../../wardrobe-service/src/transportTest/kotlin/ru/itmo/clothesadvisor/transport/RecommendationPayloadTests.kt): двадцать различных фото по 10 МБ через подготовку, Feign и декодер recommendation; проверяются все полученные байты.

## Проверка запущенного приложения

Использован отдельный Compose-проект с синтетическими данными, собственными томами и локальным портом Gateway. Канонические Dockerfile и `docker compose up --build --wait` собрали и запустили всю систему. Тестовый override уменьшал heap и направлял IAM/S3/Responses на локальные заглушки; настоящие ключи и аккаунты не использовались.

Проверены:

- UI владельца с фотографией, список клиентов и гардероб клиента у стилиста, аккаунты и просмотр коллекции у администратора.
- Общий Swagger: две реальные спецификации, GET через текущий адрес Gateway, файловые поля обеих multipart-операций, ссылка со стартовой страницы.
- Создание вещи с фото, загрузка и выдача байтов, `Location`, `no-store`, удаление метаданных и последующий 404.
- Ручные образы USER/STYLIST, запрет оценки собственного образа, лайк → дизлайк → отмена → новый голос. Удаление образа сохраняет все три исторических версии и удаляет актуальную оценку.
- AI по полной цепочке Gateway → wardrobe → recommendation → локальный Responses. Передано PNG; пустой выбор возвращает 422 без записи, `response.completed` с выбранным ID — 201 с составом и погодой. Автоматического повторного запроса не было.
- Остановка и повторный запуск с сохранением ID, связей, версий и истории. Рабочие контейнеры исходной установки не затрагивались.

Секреты разделены по потребителям: ключ Яндекса доступен wardrobe, подключение ChatGPT — recommendation. Проверены готовность сервисов, изоляция внутренних метрик и корреляция запросов. PNG/SVG архитектурных схем просмотрены; последовательность AI уточнена по фактическим проверкам и коротким транзакциям.

## Границы результата

- Реальные Yandex S3 и OpenAI в итоговом прогоне не вызывались. Заглушка подтверждает транспорт и обработку протокола, но не доступ конкретного аккаунта или качество подбора.
- Максимальный payload проверен отдельным транспортным тестом с heap 3 ГиБ. Это не нагрузочный тест и не гарантия достаточности памяти уменьшенного Compose-стенда для двадцати максимальных фото.
- Подготовлен и проверен [перенос аккаунтов](account-data-transfer.md), но исходная рабочая БД не переносилась. Перед обновлением существующей установки нужен этот runbook.
- Аутентификация, публичный хостинг, согласование БД с преподавателем и публикация ветки не входят в эту проверку. `main` и `lab1.0` сохранены.
