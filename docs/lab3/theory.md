# ЛР3: как устроена авторизация

Это рабочий документ для защиты. Готовность и проверенные сценарии отмечаются
в [плане](implementation.md); незавершённые части описаны как целевые.
В ЛР2 можно было выбрать пользователя по ID. В ЛР3 клиент должен доказать,
что знает его пароль; простая передача идентификатора больше не даёт доступ.

## Аутентификация и авторизация

Аутентификация отвечает на вопрос «кто обращается», авторизация — «что ему разрешено».
Spring Security создаёт SecurityContext после проверки Bearer token. Контроллер
получает проверенного пользователя, а сервис проверяет роль, владельца данных
и разрешение стилисту. Скрытая кнопка в интерфейсе не заменяет серверную проверку.

Права разделены на три роли: USER работает со своим гардеробом, STYLIST — с клиентом,
который дал доступ, ADMIN создаёт аккаунты и модерирует данные. Персональные разрешения
сохраняются в БД: одной роли STYLIST недостаточно для доступа к произвольному владельцу.

Источник: [Spring Security: reactive authorization](https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html).
Контроль на HTTP-границе и существующие предметные проверки решают разные задачи.

## Почему JWT и асимметричная подпись

JWT содержит claims: `sub` — ID пользователя, `iss` — издатель, `aud` — приложение,
`iat` — момент выдачи, `exp` — окончание действия. Это подписанные данные, не шифрование:
содержимое токена можно прочитать. Поэтому пароль, хеш и секреты туда не помещаются.

Account подписывает токен закрытым RSA-ключом. Gateway и wardrobe проверяют его открытым
ключом. Компрометация проверяющего сервиса не даёт ему ключ для выпуска токенов.
Алгоритм RS256 фиксируется проверяющей стороной; произвольный `alg` из заголовка не
выбирает способ проверки. Также нужны проверки издателя, аудитории, срока и subject.

Это применение [JWT Best Current Practices, RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html),
а не собственный формат авторизации. Интеграция использует
[Spring Security Reactive Resource Server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
Для небольшого учебного приложения отдельный OAuth Authorization Server не требуется:
наш endpoint входа не объявляется реализацией OAuth/OIDC. Для внешних клиентов, SSO
и сложного жизненного цикла сессий нужен отдельный выбор провайдера идентификации.

Код этапа 03: [JwtConfiguration](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/config/JwtConfiguration.kt),
[SecurityConfiguration](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/config/SecurityConfiguration.kt),
[AuthenticationService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/auth/AuthenticationService.kt).
Срок токена — 15 минут; допустимое расхождение часов — 30 секунд. Проверка требует
исходный `iat`: стандартный конвертер Spring способен восстановить его из `exp`.
Отдельный HTTP-сценарий проверяет отсутствие `iat` у токена с близким сроком истечения.

## Почему актуальные права остаются в account

JWT подтверждает личность до окончания срока, но роль и статус могли измениться после
его выпуска. Поэтому бизнес-сервисы используют свежие данные account. Это сохраняет
быструю реакцию на блокировку и отзыв разрешений, но оставляет зависимость от account:
при недоступности источника разрешение не выдаётся.

Gateway выполняет первичную проверку токена; account и wardrobe также защищают свои
HTTP API. Нельзя доверять переданному клиентом X-User-Id. Внутренние сетевые маршруты
ЛР2 при этом не становятся автоматически защищёнными от скомпрометированного контейнера:
сетевую изоляцию нельзя называть межсервисной криптографической аутентификацией.

В wardrobe проверку текущего пользователя выполняет
[CurrentUserJwtConverter](../../wardrobe-service/src/main/kotlin/ru/itmo/clothesadvisor/config/SecurityConfiguration.kt)
через `ReactiveCurrentUserService`. Резолвер аргумента контроллера извлекает уже
проверенного пользователя из principal, без второго запроса к account.
Это отличие от ЛР2, где HTTP-проверка находилась в резолвере.

`BlockingAccountClient` остаётся на границе синхронных JDBC-сценариев. Их сервисные
обёртки используют `boundedElastic`; сетевые проверки выполняются до транзакции
или после неё, в том числе повторно после S3. Ожидание занимает worker-поток,
поэтому эти сценарии не называются полностью неблокирующими. Для HTTP-проверки
пользователя такой адаптер не нужен. Подход к изоляции блокирующей работы описан
в [Reactor FAQ](https://projectreactor.io/docs/core/release/reference/faq.html#faq.wrap-blocking).

В account интерфейс `AppUserRepository` наследует `Repository`, а операции возвращают
`Mono` и `Flux` и выполняются через Spring Data R2DBC. Наследование
`ReactiveCrudRepository` не требуется для реактивного доступа: оно добавляет полный
набор CRUD-методов. Здесь оставлен узкий контракт с явными SQL-операциями для версий,
истории и времени БД, без универсального `save`. Это предусмотрено
[Spring Data: выбор методов репозитория](https://docs.spring.io/spring-data/commons/reference/repositories/definition.html).

## Пароли

Хеш пароля должен быть адаптивным, с индивидуальной случайной солью. Обычный SHA-256
слишком дешёв для перебора. Используется Spring PasswordEncoder; в PostgreSQL хранится
только результат, во внешних DTO хеша нет. Дорогая проверка пароля выполняется вне
Reactor event loop и вне транзакции БД.

Выбран PBKDF2-HMAC-SHA256: 600000 итераций, соль 16 байт, результат 256 бит. Он доступен
через стандартный Java crypto provider и Spring без дополнительного native-провайдера.
Это осознанный компромисс простоты зависимостей: OWASP в первую очередь рекомендует
Argon2id, а для PBKDF2 приводит указанный порог. Стоимость нужно измерять на целевом
сервере; число итераций не заменяет ограничение частоты попыток входа.

Источники: [Spring Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html),
[OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
Хеши нельзя возвращать даже администратору; пароль задаётся заново, а не восстанавливается.

Код этапа 02: [PasswordConfiguration](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/config/PasswordConfiguration.kt),
[PasswordService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/auth/PasswordService.kt),
[CredentialAuthenticationService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/auth/CredentialAuthenticationService.kt).
Формат хеша содержит идентификатор параметров. Заглушка `!` и неизвестные форматы
не принимаются; для такого аккаунта понадобится назначение пароля администратором.
Эти сервисы используются при публичном входе через account-service.

Назначение пароля администратором не означает немедленного отзыва уже выданного JWT:
он действует до `exp` с допуском часов. Для немедленного запрета запросов используется
блокировка аккаунта. Это ограничение схемы без проверки отзыва каждого access token.
Refresh-сессии решают другую задачу: выход запрещает дальнейшее обновление текущей
сессии, смена пароля — всех сессий пользователя. Они не отзывают копии уже выданного JWT.

Код этапа 04: [UserManagementService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/user/UserManagementService.kt)
хеширует пароль перед транзакцией, затем
[AdminUserService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/user/AdminUserService.kt)
блокирует нужные записи и проверяет текущие права администратора.
Первоначальный ADMIN настраивается через окружение; значения читаются как данные,
без SpEL и подстановок Spring. Повторный запуск не меняет уже установленный пароль.

## Browser, CSRF и транспорт

Целевой интерфейс хранит короткий access token в памяти вкладки. HTTP-клиент явно
прикладывает `Authorization: Bearer …` к JSON, multipart и запросам фотографий.
Refresh-cookie использует `HttpOnly`, `SameSite=Strict`, host-only,
с путём `/api/auth` и `Secure` по умолчанию. Javascript не читает её значение;
браузер передаёт cookie при обновлении сессии. Вход восстанавливается после перезагрузки,
но через 7 дней с исходного входа потребуется пароль. Повторное обновление срок не продлевает.

Refresh — случайные 32 байта; account хранит SHA-256 хеш. Быстрый хеш подходит здесь
из-за высокой случайной энтропии токена, но не подходит для человеческих паролей.
Каждое обновление потребляет старый токен и выпускает новый. Использованные хеши
сохраняются, чтобы распознать повтор любого предыдущего токена и отозвать его сессию.
Повтор может означать кражу или гонку запросов; безопасный результат в обоих случаях —
новый вход. Frontend должен объединять одновременные обновления и не повторять refresh
автоматически после потери ответа. Схема следует принципам
[RFC 9700, раздел 4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14),
но наш API входа не является OAuth Authorization Server.

Cookie добавляет риск CSRF. Для POST входа, refresh и выхода требуется отдельный
заголовок `X-Auth-Request: 1`; cross-origin CORS не разрешён. Сторонняя HTML-форма
не может добавить такой заголовок, а междоменный fetch должен пройти preflight.
Это описанный OWASP вариант для AJAX/API, а не секретный токен.
`SameSite=Strict` — дополнительный барьер. Если понадобится разрешить другой origin,
эту границу нужно пересмотреть. Защита от XSS остаётся необходимой.

Источники: [OWASP: custom request headers](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#employing-custom-request-headers-for-ajaxapi),
[OWASP: session cookies](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookies).

Код: [AuthenticationService](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/service/auth/AuthenticationService.kt),
[AuthSessionRepository](../../account-service/src/main/kotlin/ru/itmo/clothesadvisor/accountservice/repository/auth/AuthSessionRepository.kt),
[миграция V3](../../account-service/src/main/resources/db/migration/V3__create_auth_sessions.sql).
Ротация и смена пароля сначала блокируют запись пользователя. Отзыв при повторе
refresh завершается коммитом, и только затем формируется 401; ошибка внутри
транзакции откатила бы нужный отзыв. Использованные хеши пока не очищаются автоматически;
просроченные записи никогда не разрешают обновление. Фильтр и маршрутизация используют
одинаковый matcher Spring: закодированные буквы в URL не обходят CSRF-проверку.

При будущем обновлении удалённого стенда Basic Auth обратного прокси нельзя оставлять
перед теми же API: он использует тот же заголовок Authorization и конфликтует с Bearer.
ЛР3 пока не меняет удалённый сервер; административные интерфейсы можно защищать отдельно.

Источники: [Spring Security CSRF](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html),
[Bearer Token Usage, RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html).
На удалённом стенде логин/пароль и Bearer передаются через HTTPS. Локальный HTTP
допустим только для учебного стенда на loopback. Ключи и bootstrap-пароль не коммитятся.

## Что показать на защите

1. Без токена бизнес-API возвращает 401; поддельный X-User-Id не помогает.
2. Вход даёт JWT, с ним USER читает свой гардероб, но не создаёт аккаунт.
3. ADMIN создаёт пользователя; в ответе нет пароля/хеша, в БД находится хеш.
4. STYLIST получает доступ только после разрешения владельца.
5. После блокировки следующий запрос не проходит, даже если срок JWT ещё не истёк.
6. Неверная подпись, аудитория и истёкший токен отклоняются.

Команды и ссылки на окончательные классы будут добавлены по результатам реализации,
а не представлены как уже выполненные проверки.
