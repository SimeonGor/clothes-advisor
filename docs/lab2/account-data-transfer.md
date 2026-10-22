# Перенос аккаунтов в отдельную БД

Этап 16 готовит процедуру и проверяет её на синтетических данных PostgreSQL 18.
Рабочая БД здесь не переносится. Переключение сервисов выполняется на этапе 17.
Приложение не запускает эту процедуру автоматически.

Переносятся только `public.app_user`, `public.app_user_history`,
`public.access_grant` и `public.app_user_id_seq`. Сохраняются все существующие
колонки, ключи, хеши паролей, роли, статусы, версии и даты. История пользователей
по текущей схеме не содержит пароль и дату создания: новые поля не добавляются.
`flyway_schema_history`, гардероб и его история остаются в исходной БД.

## Условия и подготовка

Процедура рассчитана на исходную схему wardrobe V1–V10 и целевую account V1–V2
из этого репозитория, стандартную sequence с шагом 1 и PostgreSQL 18 с обеих сторон.
Используйте `pg_dump` и `psql` той же основной версии. При дополнительных колонках,
изменённых типах, sequence или миграциях остановитесь и отдельно согласуйте перенос.
Манифест проверяет данные, а не совместимость произвольных схем.

1. Выделите окно обслуживания. Остановите gateway и все процессы, которые могут
   писать в обе БД: wardrobe, account, фоновые задания, ручные сессии и локальные
   инициализаторы. Сохраняйте запрет записи до завершения проверки и переключения.
   Даже чтение `nextval` меняет состояние sequence и в это окно запрещено.
2. Подготовьте libpq service `wardrobe-source` и `account-target` с правильными
   адресами и именами БД. Убедитесь, что это разные БД и назначение второй —
   `account_db`. Пароли задаются вне Git и командной строки через защищённые
   `.pg_service.conf`/`.pgpass`. Не копируйте их в отчёт, команды или историю чата.
3. Выполняйте команды из корня репозитория в отдельной Bash-сессии. Создайте
   приватный каталог вне репозитория и проверьте свободное место. В примере
   `/absolute/private/backup-dir` нужно заменить существующим закрытым каталогом.

```bash
set -euo pipefail
umask 077
transfer_dir="$(mktemp -d /absolute/private/backup-dir/account-transfer.XXXXXX)"
pg_dump --version
psql --version
psql -X -qAt --set=ON_ERROR_STOP=1 --command='SHOW server_version' \
  'service=wardrobe-source' > "$transfer_dir/source-version.txt" \
  2> "$transfer_dir/source-version.log"
psql -X -qAt --set=ON_ERROR_STOP=1 --command='SHOW server_version' \
  'service=account-target' > "$transfer_dir/target-version.txt" \
  2> "$transfer_dir/target-version.log"
```

Проверьте версии в приватных файлах. При любой ошибке остановитесь; обе БД остаются
без писателей. Дампы содержат хеши паролей и персональные данные, а сообщения COPY
могут содержать строку целиком. Не выводите дампы и журналы в общие логи, не
добавляйте их в Git и не отправляйте в CI-артефакты.

## Резервные копии и целевая схема

До изменения цели сохраните полные логические резервные копии обеих БД. Существующие
роли и инфраструктурные настройки должны быть доступны по отдельной эксплуатационной
процедуре: `pg_dump` не резервирует весь кластер PostgreSQL.

```bash
pg_dump --format=custom --file="$transfer_dir/wardrobe-before.dump" \
  'service=wardrobe-source' 2> "$transfer_dir/wardrobe-backup.log"
pg_dump --format=custom --file="$transfer_dir/account-before.dump" \
  'service=account-target' 2> "$transfer_dir/account-backup.log"
```

Проверьте доступность резервных копий и возможность восстановления в отдельную
тестовую БД по принятой процедуре до продолжения. Сохраните исходную БД и её копию.

Если account-схема ещё не создана, примените штатные миграции account V1–V2 через
Flyway приложения. Запускайте account без профиля `local`, без публичного доступа
и других писателей, затем снова остановите его. Локальный инициализатор может
заполнить цель: такую БД нельзя импортировать поверх существующих данных.
Проверьте успешные версии Flyway: source — V1–V10, target — V1–V2.
Не копируйте таблицу Flyway из source в target.

```bash
psql -X -qAt --set=ON_ERROR_STOP=1 \
  --command='SELECT version, success FROM public.flyway_schema_history ORDER BY installed_rank' \
  'service=wardrobe-source' > "$transfer_dir/source-migrations.txt" \
  2> "$transfer_dir/source-migrations.log"
psql -X -qAt --set=ON_ERROR_STOP=1 \
  --command='SELECT version, success FROM public.flyway_schema_history ORDER BY installed_rank' \
  'service=account-target' > "$transfer_dir/target-migrations.txt" \
  2> "$transfer_dir/target-migrations.log"
```

## Экспорт и проверка неподвижности источника

Манифест содержит количество строк и SHA-256 полных JSONB-строк каждой из трёх
таблиц, упорядоченных по первичному ключу, а также `last_value` и `is_called`
sequence. Время приводится к UTC. Пустая таблица хешируется как `[]`.
Хеш пароля участвует в вычислении, но сами строки и пароли не печатаются.
Проверка не вызывает `nextval` и не меняет данные.

```bash
psql -X -qAt --set=ON_ERROR_STOP=1 --file=ops/account-transfer/manifest.sql \
  'service=wardrobe-source' > "$transfer_dir/source-before.manifest" \
  2> "$transfer_dir/source-before.log"
pg_dump --data-only --no-owner --no-privileges --strict-names \
  --table=public.app_user --table=public.app_user_history \
  --table=public.access_grant --table=public.app_user_id_seq \
  --file="$transfer_dir/account-data.sql" 'service=wardrobe-source' \
  2> "$transfer_dir/export.log"
psql -X -qAt --set=ON_ERROR_STOP=1 --file=ops/account-transfer/manifest.sql \
  'service=wardrobe-source' > "$transfer_dir/source-after.manifest" \
  2> "$transfer_dir/source-after.log"
cmp -s "$transfer_dir/source-before.manifest" "$transfer_dir/source-after.manifest"
```

Все четыре объекта указаны явно, включая sequence. Её состояние может быть выше
`MAX(id)` и не должно вычисляться заново. `pg_dump` читает согласованный снимок строк,
но sequence и два манифеста не принадлежат одному MVCC-снимку. Совпадение файлов
выявляет часть нарушений запрета записи и **не заменяет остановку писателей**.
Это офлайн-процедура; перенос без остановки записи не поддерживается.

## Импорт и сверка

```bash
psql -X --set=ON_ERROR_STOP=1 --single-transaction \
  --file=ops/account-transfer/require-empty-target.sql \
  --file="$transfer_dir/account-data.sql" 'service=account-target' \
  > "$transfer_dir/import.log" 2> "$transfer_dir/import-errors.log"
psql -X -qAt --set=ON_ERROR_STOP=1 --file=ops/account-transfer/manifest.sql \
  'service=account-target' > "$transfer_dir/target.manifest" \
  2> "$transfer_dir/target-manifest.log"
cmp -s "$transfer_dir/source-before.manifest" "$transfer_dir/target.manifest"
psql -X -qAt --set=ON_ERROR_STOP=1 --file=ops/account-transfer/manifest.sql \
  'service=wardrobe-source' > "$transfer_dir/source-final.manifest" \
  2> "$transfer_dir/source-final.log"
cmp -s "$transfer_dir/source-before.manifest" "$transfer_dir/source-final.manifest"
```

Первый SQL-файл блокирует все три таблицы и отказывает, если хотя бы одна непуста.
Блокировки удерживаются до завершения общей транзакции CLI. Здесь нет `TRUNCATE`,
upsert или перезаписи цели. Повторный импорт успешно перенесённых данных также
отказывается выполняться. Повторять чтение манифеста безопасно.

Ошибка COPY откатывает изменения таблиц. Однако PostgreSQL `setval` не откатывается:
если ошибка произошла после его выполнения, sequence уже изменена. После любого
неуспешного импорта цель остаётся выключенной; оператор восстанавливает её из
резервной копии или заново создаёт отдельную пустую цель. Не повторяйте импорт
вслепую и не включайте писателей на частично проверенной БД.

Разный манифест, в том числе при одинаковом числе строк, блокирует переключение.
Не исправляйте расхождение автоматически. Проверьте причину в приватной среде;
повторите перенос только после восстановления согласованного состояния и остановки
всех писателей. Обычная проверка sequence не должна потреблять новый ID.

## Связи гардероба и граница этапа 17

На этапе 16 перечисленные внешние ключи сохраняются в рабочей wardrobe-БД:

| Ссылка на `app_user.id` | Ограничение |
| --- | --- |
| `wardrobe_item.owner_id` | `wardrobe_item_owner_id_fkey` |
| `outfit.owner_id` | `outfit_owner_id_fkey` |
| `outfit.author_id` | `outfit_author_id_fkey` |
| `outfit_rating.stylist_id` | `outfit_rating_stylist_id_fkey` |
| `outfit_rating_history.stylist_id` | `outfit_rating_history_stylist_id_fkey` |

Перед переключением подтвердите, что эти пять FK присутствуют и валидированы.
При них все исходные ссылки разрешаются в исходные аккаунты, а совпавшие полные
манифесты подтверждают сохранение их ID в цели. История оценок удалённого образа
тоже содержит ссылку на стилиста и должна сохраняться. Три внутренних FK account
(`app_user_history.user_id`, `access_grant.owner_id` и `access_grant.stylist_id`)
остаются действующими.

`ops/account-transfer/detach-user-foreign-keys.sql` подготовлен только для этапа 17.
**Не применяйте его к рабочей БД до согласованного переключения.** Он находится
вне Flyway и сам не запускается. На этапе 17 его содержимое нужно перенести в новую
миграцию wardrobe, не редактируя старые миграции. В репетиции этот файл исполняется
в транзакции на синтетической БД, проверяются ровно пять снятых FK, сохранение строк
и последующий `ROLLBACK`, восстанавливающий ограничения.

Точка переключения требует одновременно:

- равенства манифестов и сохранения всех пользовательских ссылок;
- account как единственного писателя пользователей, истории и разрешений;
- перевода всех потребителей wardrobe на account и настройки маршрутов gateway;
- выключенного старого инициализатора пользователей и старого пути записи;
- сохранённых резервных копий и решения оператора о продолжении.

Эти изменения поведения сервисов не реализуются этапом 16. До первой новой записи
в account можно отказаться от переключения и оставить прежнего писателя с
неизменённым source. После первой записи в account простое включение старого
писателя потеряет изменения: сначала остановите все записи, затем восстановите
согласованные резервные копии либо выполните отдельно согласованную обратную
сверку и перенос. Автоматического отката, двойной записи или fallback здесь нет.

## Воспроизводимая репетиция

```bash
./gradlew :account-service:test --tests '*AccountDataTransferTests' \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

Нужен доступный Docker для Testcontainers. Тест владеет отдельным контейнером
`postgres:18-alpine`, создаёт новые source/target БД для каждого случая, применяет
штатные Flyway-схемы и запускает настоящие `pg_dump`/`psql` внутри контейнера.
Рабочие подключения, `.env` и реальные данные не используются. Дампы остаются
в приватном каталоге контейнера и исчезают при его удалении.

| Сценарий | Новый контракт (`ADDED`) |
| --- | --- |
| AC1 | Полные строки, версии, даты, хеши, роли, BLOCKED-владелец, история, grants, разрыв sequence; неизменный гардероб и ссылки, включая историю удалённого образа |
| AC2 | Пустая и заполненная БД, `is_called=false/true`, точный следующий синтетический ID, повторное чтение манифеста без изменения sequence |
| AC3 | Заполненная цель и повторный импорт отклоняются; malformed COPY откатывает строки, но не `setval`; подмена хеша при том же числе строк меняет манифест |
| AC4 | Ровно пять внешних FK снимаются в транзакции, три внутренних остаются, строки сохраняются, откат возвращает FK |
| AC5 | Эти CLI-команды, SQL и миграции источника входят в проверяемую репетицию; общий `check`/`bootJar` проверяет регрессию приложения |

Это новые помощники без исторического baseline. Поведение существующего приложения
должно сохраняться (`PRESERVED`); его регрессионную проверку выполняет общий набор
тестов. Компиляция сама по себе не доказывает перенос; результаты репетиции, общей
проверки и независимого ревью фиксируются отдельно перед завершением этапа.
