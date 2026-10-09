"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const PAGE_SIZE = 12;
  const ROLE_NAMES = { USER: "Пользователь", STYLIST: "Стилист", ADMIN: "Администратор" };
  const SOURCE_NAMES = { USER: "Вручную", STYLIST: "От стилиста", AI: "Подобрано AI" };
  let actor = null;
  let sessionScope = null;
  let viewScope = null;
  let dialogScope = null;
  let view = null;
  let busy = false;
  let dialogDirty = false;

  // Every read, cache and object URL belongs to an immutable actor/owner context.
  // Disposing a parent recursively aborts its reads and revokes its photo URLs.
  class Scope {
    constructor(context, parent = null) {
      this.context = Object.freeze({ ...context });
      this.parent = parent;
      this.controller = new AbortController();
      this.children = new Set();
      this.urls = new Set();
      this.items = new Map();
      this.photos = new Map();
      this.images = new Map();
      if (parent) parent.children.add(this);
    }

    get alive() {
      return !this.controller.signal.aborted && (!this.parent || this.parent.alive);
    }

    check() {
      if (!this.alive) throw new DOMException("Устаревший запрос", "AbortError");
    }

    child() {
      return new Scope(this.context, this);
    }

    dispose() {
      this.controller.abort();
      for (const child of [...this.children]) child.dispose();
      for (const url of this.urls) URL.revokeObjectURL(url);
      this.urls.clear();
      this.items.clear();
      this.photos.clear();
      this.images.clear();
      this.parent?.children.delete(this);
    }
  }

  // This route table is also the operation boundary for each owner context.
  function routes(context) {
    const base = context.kind === "client"
      ? `/api/stylist/clients/${context.ownerId}`
      : context.kind === "admin" ? `/api/admin/users/${context.ownerId}` : "/api";
    return Object.freeze({
      items: `${base}/wardrobe/items`,
      outfits: `${base}/outfits`,
      editItems: context.kind === "own",
      deletePhotos: context.kind !== "client",
      createOutfits: context.kind !== "admin",
      deleteOutfits: context.kind !== "client",
      history: context.kind !== "admin",
      rate: context.kind === "client",
      ai: context.kind === "own" && context.role === "USER",
    });
  }

  function node(tag, className = "", text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (className.split(" ").includes("status")) {
      element.setAttribute("role", "status");
      element.setAttribute("aria-live", "polite");
    }
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function button(text, handler, className = "quiet") {
    const control = node("button", className, text);
    control.type = "button";
    control.addEventListener("click", () => {
      if (!busy) handler(control);
    });
    return control;
  }

  function setStatus(target, message = "", error = false) {
    target.replaceChildren();
    if (message) target.append(node("span", "", message));
    target.classList.toggle("error", error);
  }

  function messageFor(error) {
    const messages = {
      400: "Проверьте заполненные поля и выбранные вещи.",
      401: "Не удалось определить выбранный аккаунт.",
      403: "Действие недоступно: проверьте роль, статус аккаунта или доступ к клиенту.",
      404: "Объект больше не доступен. Обновите список или заново откройте карточку.",
      409: "Конфликт данных. Закройте и заново откройте карточку для актуальной версии. Вещь также может использоваться в образе.",
      413: "Файл слишком большой. Каждая фотография должна быть не более 10 МБ.",
      415: "Поддерживаются только фотографии JPEG и PNG.",
      422: "AI не смог составить образ. Проверьте фотографии и состав кандидатов.",
      502: "Внешний сервис вернул ошибку. Автоматического повтора не будет.",
      503: "Сервис временно недоступен или AI не настроен для этого аккаунта.",
      504: "Истекло время ожидания сервиса. Проверьте список перед повторным созданием.",
    };
    if (error.status) return messages[error.status] || `Ошибка сервера (${error.status}).`;
    if (error instanceof TypeError) return "Не удалось связаться с сервером. После отправки изменения его результат может быть неизвестен: обновите данные перед повтором.";
    return error.message || "Не удалось выполнить действие.";
  }

  function report(error, scope, target, retry) {
    if (!scope.alive || error.name === "AbortError") return;
    setStatus(target, messageFor(error), true);
    if (retry) target.append(button("Повторить загрузку", retry));
  }

  async function request(scope, path, { method = "GET", body, blob = false } = {}) {
    scope.check();
    const headers = new Headers();
    if (scope.context.actorId != null) headers.set("X-User-Id", String(scope.context.actorId));
    if (body && !(body instanceof FormData)) {
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(body);
    }
    const response = await fetch(path, {
      method, headers, body,
      credentials: "same-origin",
      cache: "no-store",
      // A sent mutation must finish; navigation is guarded while it is pending.
      signal: method === "GET" ? scope.controller.signal : undefined,
    });
    scope.check();
    if (!response.ok) {
      // Domain errors may be empty; proxy HTML is not an application message.
      const error = new Error(`HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    const data = response.status === 204 ? null : blob ? await response.blob() : await response.json();
    scope.check();
    const totalHeader = response.headers.get("X-Total-Count");
    return { data, total: totalHeader === null ? null : Number(totalHeader) };
  }

  function nextPage(result, page, size = PAGE_SIZE) {
    return result.total === null ? result.data.length === size : (page + 1) * size < result.total;
  }

  function pageUrl(path, page, size = PAGE_SIZE) {
    return `${path}?page=${Math.max(0, page)}&size=${size}`;
  }

  async function allPages(scope, path) {
    const entries = [];
    for (let page = 0; ; page++) {
      const result = await request(scope, pageUrl(path, page, 50));
      scope.check();
      entries.push(...result.data);
      if (!nextPage(result, page, 50) || !result.data.length) return entries;
    }
  }

  function pager(target, result, page, change) {
    target.replaceChildren();
    const previous = button("← Назад", () => change(page - 1));
    previous.disabled = page === 0;
    const next = button("Далее →", () => change(page + 1));
    next.disabled = !nextPage(result, page);
    target.append(previous, node("span", "", `Страница ${page + 1}${result.total === null ? "" : ` · Всего: ${result.total}`}`), next);
  }

  async function mutate(scope, status, operation, pending = "Сохраняем…") {
    if (busy || !scope.alive) return;
    busy = true;
    setStatus(status, pending);
    const controls = [...document.querySelectorAll("button, input, select")].map((control) => [control, control.disabled]);
    controls.forEach(([control]) => { control.disabled = true; });
    const regions = [$("app"), $("login"), $("detail-content")];
    regions.forEach((region) => { region.inert = true; });
    document.body.setAttribute("aria-busy", "true");
    try {
      await operation();
    } catch (error) {
      report(error, scope, status);
      if (scope.alive && error.name !== "AbortError") status.scrollIntoView({ block: "nearest" });
    } finally {
      busy = false;
      document.body.removeAttribute("aria-busy");
      regions.forEach((region) => { region.inert = false; });
      controls.forEach(([control, disabled]) => {
        if (control.isConnected) control.disabled = disabled;
      });
    }
  }

  window.addEventListener("beforeunload", (event) => {
    if (busy) {
      event.preventDefault();
      event.returnValue = "";
    }
  });

  function closeDialog(refresh = true) {
    dialogScope?.dispose();
    dialogScope = null;
    $("detail-dialog").close();
    $("detail-content").replaceChildren();
    const dirty = dialogDirty;
    dialogDirty = false;
    if (refresh && dirty) loadView();
  }

  function openDialog(title) {
    closeDialog(false);
    dialogScope = viewScope.child();
    $("detail-title").textContent = title;
    $("dialog-context").textContent = contextLabel(dialogScope.context);
    setStatus($("dialog-status"));
    $("detail-dialog").showModal();
    return dialogScope;
  }

  $("close-dialog").addEventListener("click", () => { if (!busy) closeDialog(); });
  $("detail-dialog").addEventListener("cancel", (event) => {
    event.preventDefault();
    if (!busy) closeDialog();
  });

  function contextLabel(context) {
    if (context.kind === "client") return `Клиент: ${context.ownerLogin} · Работа стилиста`;
    if (context.kind === "admin") return `Владелец: ${context.ownerLogin} · Модерация`;
    return `Личная коллекция · ${context.ownerLogin || ""}`;
  }

  function ownContext() {
    return { actorId: actor.id, role: actor.role, kind: "own", ownerId: actor.id, ownerLogin: actor.login };
  }

  async function loadAccounts() {
    sessionScope?.dispose();
    sessionScope = new Scope({ actorId: null });
    const scope = sessionScope;
    $("account-picker").replaceChildren();
    $("login-submit").disabled = true;
    setStatus($("login-status"), "Загружаем аккаунты…");
    try {
      const accounts = await allPages(scope, "/api/admin/users");
      scope.check();
      const active = accounts.filter((account) => account.status === "ACTIVE");
      fillOptions($("account-picker"), active, (account) => `${account.login} · ${ROLE_NAMES[account.role] || account.role}`);
      $("login-submit").disabled = !active.length;
      setStatus($("login-status"), active.length ? "" : "Нет активных аккаунтов.");
    } catch (error) {
      report(error, scope, $("login-status"), loadAccounts);
    }
  }

  $("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy) return;
    const scope = sessionScope;
    const id = $("account-picker").value;
    if (!id) return;
    $("login-submit").disabled = true;
    try {
      const result = await request(scope, `/api/admin/users/${encodeURIComponent(id)}`);
      scope.check();
      if (result.data.status !== "ACTIVE") throw new Error("Аккаунт заблокирован. Обновите список аккаунтов.");
      actor = result.data;
      scope.dispose();
      sessionScope = new Scope(ownContext());
      $("login").hidden = true;
      $("app").hidden = false;
      $("account").textContent = `${actor.login} · ${ROLE_NAMES[actor.role] || actor.role}`;
      buildNavigation();
      navigate("wardrobe", ownContext());
    } catch (error) {
      report(error, scope, $("login-status"), loadAccounts);
    } finally {
      if (scope.alive) $("login-submit").disabled = false;
    }
  });

  $("change-account").addEventListener("click", () => {
    if (busy) return;
    closeDialog(false);
    viewScope?.dispose();
    actor = null;
    view = null;
    $("cards").replaceChildren();
    $("app").hidden = true;
    $("login").hidden = false;
    loadAccounts();
  });

  function buildNavigation() {
    const navigation = $("navigation");
    navigation.replaceChildren();
    const entries = [["wardrobe", "Мой гардероб"], ["outfits", "Мои образы"]];
    if (actor.role === "USER") entries.push(["access", "Доступ стилистов"]);
    if (actor.role === "STYLIST") entries.push(["clients", "Клиенты"]);
    if (actor.role === "ADMIN") entries.push(["accounts", "Аккаунты"]);
    for (const [name, title] of entries) {
      const control = button(title, () => navigate(name, ownContext()), "nav-button");
      control.dataset.view = name;
      navigation.append(control);
    }
  }

  function navigate(section, context, page = 0) {
    closeDialog(false);
    view = Object.freeze({ section, context: Object.freeze({ ...context }), page });
    loadView();
  }

  $("refresh").addEventListener("click", () => { if (!busy) loadView(); });
  $("create").addEventListener("click", () => {
    if (busy) return;
    if (view.section === "wardrobe") openItem();
    if (view.section === "outfits") openOutfitForm();
  });

  async function loadView() {
    closeDialog(false);
    viewScope?.dispose();
    const snapshot = view;
    viewScope = new Scope(snapshot.context, sessionScope);
    const scope = viewScope;
    const route = routes(scope.context);
    $("cards").replaceChildren();
    $("pagination").replaceChildren();
    $("page-tools").replaceChildren();
    $("owner-navigation").replaceChildren();
    setStatus($("page-status"), "Загружаем…");
    const titles = { wardrobe: "Гардероб", outfits: "Образы", access: "Доступ стилистов", clients: "Мои клиенты", accounts: "Аккаунты" };
    $("page-title").textContent = titles[snapshot.section];
    $("view-eyebrow").textContent = contextLabel(scope.context);
    const subtitles = {
      wardrobe: "Вещи и фотографии, из которых складывается ваш стиль.",
      outfits: "Сочетания для погоды, оценки и детали каждого образа.",
      access: "Стилист увидит весь гардероб и образы. После отзыва доступа оценки сохраняются.",
      clients: "Здесь только клиенты, предоставившие вам доступ.",
      accounts: "Роли, статусы и модерация коллекций существующих аккаунтов.",
    };
    $("page-subtitle").textContent = subtitles[snapshot.section];
    $("create").hidden = !(snapshot.section === "wardrobe" && route.editItems || snapshot.section === "outfits" && route.createOutfits);
    $("create").textContent = snapshot.section === "wardrobe" ? "+ Добавить вещь" : "+ Создать образ";
    for (const control of $("navigation").children) {
      const active = scope.context.kind === "own" && control.dataset.view === snapshot.section;
      if (active) control.setAttribute("aria-current", "page");
      else control.removeAttribute("aria-current");
    }
    if (scope.context.kind !== "own") {
      const ownerNav = $("owner-navigation");
      ownerNav.append(node("p", "notice", contextLabel(scope.context)));
      const actions = node("div", "actions");
      actions.append(
        button("Гардероб владельца", () => navigate("wardrobe", scope.context), snapshot.section === "wardrobe" ? "secondary" : "quiet"),
        button("Образы владельца", () => navigate("outfits", scope.context), snapshot.section === "outfits" ? "secondary" : "quiet"),
        button("← К списку", () => navigate(scope.context.kind === "client" ? "clients" : "accounts", ownContext())),
      );
      ownerNav.append(actions);
    }
    const paths = { wardrobe: route.items, outfits: route.outfits, access: "/api/me/stylist-access", clients: "/api/stylist/clients", accounts: "/api/admin/users" };
    try {
      const [result, references] = await Promise.all([
        request(scope, pageUrl(paths[snapshot.section], snapshot.page)),
        snapshot.section === "wardrobe"
          ? allPages(scope, "/api/wardrobe/categories")
          : snapshot.section === "outfits"
            ? allPages(scope, "/api/weather/precipitation-types")
            : Promise.resolve([]),
      ]);
      scope.check();
      const renderers = { wardrobe: itemCard, outfits: outfitCard, access: accessCard, clients: clientCard, accounts: accountCard };
      for (const entry of result.data) $("cards").append(renderers[snapshot.section](entry, scope, references));
      if (!result.data.length) {
        const empty = node("section", "empty");
        empty.append(node("h2", "", snapshot.page ? "На этой странице пусто" : "Пока ничего нет"));
        empty.append(node("p", "muted", snapshot.page ? "Вернитесь на предыдущую страницу." : "Новые записи появятся здесь."));
        $("cards").append(empty);
      }
      pager($("pagination"), result, snapshot.page, (page) => navigate(snapshot.section, snapshot.context, page));
      setStatus($("page-status"));
      if (snapshot.section === "access") loadGrantForm(scope);
    } catch (error) {
      report(error, scope, $("page-status"), loadView);
    }
  }

  function fillOptions(select, entries, label = (entry) => entry.name) {
    select.replaceChildren();
    for (const entry of entries) {
      const option = node("option", "", label(entry));
      option.value = entry.id;
      select.append(option);
    }
  }

  function field(form, title, name, { type = "text", value = "", maxLength, min, required = true } = {}) {
    const label = node("label", "", title);
    const input = node(type === "select" ? "select" : "input");
    input.name = name;
    if (type !== "select") input.type = type;
    input.required = required;
    if (maxLength) input.maxLength = maxLength;
    if (min !== undefined) input.min = min;
    if (type === "number") input.step = "any";
    input.value = value;
    label.append(input);
    form.append(label);
    return input;
  }

  function submitButton(form, title) {
    const control = node("button", "primary wide", title);
    control.type = "submit";
    form.append(control);
    return control;
  }

  function requiredText(form, name) {
    const value = form.elements[name].value.trim();
    if (!value) throw new Error("Заполните обязательные поля: одни пробелы не подходят.");
    return value;
  }

  function date(value) {
    return value ? new Date(value).toLocaleString("ru-RU") : "—";
  }

  function itemData(scope, id) {
    if (!scope.items.has(id)) {
      scope.items.set(id, request(scope, `${routes(scope.context).items}/${id}`).then((result) => result.data));
    }
    return scope.items.get(id);
  }

  function photoData(scope, id) {
    if (!scope.photos.has(id)) {
      scope.photos.set(id, request(scope, `${routes(scope.context).items}/${id}/photos`).then((result) => result.data));
    }
    return scope.photos.get(id);
  }

  async function photoImage(scope, item, photo, target) {
    if (!scope.images.has(photo.id)) {
      const path = `${routes(scope.context).items}/${item.id}/photos/${photo.id}/content`;
      const imageUrl = request(scope, path, { blob: true }).then(({ data }) => {
        scope.check();
        const url = URL.createObjectURL(data);
        scope.urls.add(url);
        return url;
      }).catch((error) => {
        scope.images.delete(photo.id);
        throw error;
      });
      scope.images.set(photo.id, imageUrl);
    }
    const url = await scope.images.get(photo.id);
    scope.check();
    const image = node("img");
    image.alt = `Фото: ${item.name}`;
    image.src = url;
    image.addEventListener("error", () => {
      URL.revokeObjectURL(url);
      scope.urls.delete(url);
      scope.images.delete(photo.id);
      if (scope.alive) image.replaceWith(node("p", "fine", "Не удалось показать фото"));
    }, { once: true });
    target.replaceChildren(image);
  }

  async function preview(scope, item, target) {
    try {
      const photos = await photoData(scope, item.id);
      scope.check();
      if (photos.length) await photoImage(scope, item, photos[0], target);
      else target.replaceChildren(node("span", "fine", "Без фотографии"));
    } catch (error) {
      if (scope.alive && error.name !== "AbortError") {
        target.replaceChildren(node("span", "fine", "Фото недоступно"), button("Повторить", () => {
          scope.photos.delete(item.id);
          preview(scope, item, target);
        }));
      }
    }
  }

  function itemCard(item, scope, categories) {
    scope.items.set(item.id, Promise.resolve(item));
    const card = node("article", "card");
    const image = node("div", "card-image", "Загрузка фото…");
    const category = categories.find((entry) => entry.id === item.categoryId);
    card.append(image, node("span", "badge", category?.name || `Категория #${item.categoryId}`));
    card.append(node("h3", "", item.name), node("p", "muted", `${item.color} · ${item.material}`));
    const actions = node("div", "actions");
    actions.append(button(routes(scope.context).editItems ? "Изменить / фото" : "Подробнее / фото", () => openItem(item.id)));
    card.append(actions);
    preview(scope, item, image);
    return card;
  }

  function outfitCard(outfit, scope, precipitation) {
    const card = node("article", "card");
    const collage = node("div", "collage");
    card.append(collage, node("span", "badge", SOURCE_NAMES[outfit.source] || outfit.source), node("h3", "", outfit.name));
    const precipitationName = precipitation.find((entry) => entry.id === outfit.weather.precipitationTypeId)?.name || `Осадки #${outfit.weather.precipitationTypeId}`;
    card.append(node("p", "muted", `${weatherText(outfit.weather)} · ${precipitationName}`));
    const composition = node("ul", "composition");
    outfit.itemIds.forEach((id, index) => {
      const name = node("li", "", `Вещь #${id} · загрузка…`);
      composition.append(name);
      const image = index < 4 ? node("div", "card-image", "Загрузка…") : null;
      if (image) collage.append(image);
      itemData(scope, id).then((item) => {
        scope.check();
        name.textContent = item.name;
        if (image) preview(scope, item, image);
      }).catch((error) => {
        if (scope.alive && error.name !== "AbortError") {
          name.textContent = `Вещь #${id} · недоступна`;
          if (image) image.textContent = "Вещь недоступна";
        }
      });
    });
    card.append(composition, node("p", "rating-summary", `Нравится: ${outfit.likes} · Не нравится: ${outfit.dislikes}`));
    if (routes(scope.context).rate && outfit.myRating) card.append(node("p", "fine", `Ваша оценка: ${voteName(outfit.myRating.vote)}`));
    const actions = node("div", "actions");
    actions.append(button("Открыть образ", () => openOutfit(outfit.id)));
    card.append(actions);
    return card;
  }

  function weatherText(weather) {
    return `${weather.temperatureC} °C · ветер ${weather.windSpeedMps} м/с`;
  }

  function accountCard(account, scope) {
    const card = node("article", "card");
    card.append(node("span", "badge", account.status === "ACTIVE" ? "Активен" : "Заблокирован"));
    card.append(node("h3", "", account.login), node("p", "muted", ROLE_NAMES[account.role] || account.role));
    const actions = node("div", "actions");
    actions.append(button("Аккаунт / изменить", () => openAccount(account.id)));
    actions.append(button("Коллекция", () => navigate("wardrobe", { ...scope.context, kind: "admin", ownerId: account.id, ownerLogin: account.login })));
    card.append(actions);
    return card;
  }

  function clientCard(client, scope) {
    const card = node("article", "card");
    card.append(node("span", "badge", "Доступ предоставлен"), node("h3", "", client.login));
    const context = { ...scope.context, kind: "client", ownerId: client.id, ownerLogin: client.login };
    const actions = node("div", "actions");
    actions.append(button("Гардероб клиента", () => navigate("wardrobe", context)));
    actions.append(button("Образы клиента", () => navigate("outfits", context)));
    card.append(actions);
    return card;
  }

  function accessCard(recipient, scope) {
    const card = node("article", "card");
    card.append(node("span", "badge", "Доступ к коллекции"), node("h3", "", recipient.login));
    card.append(node("p", "fine", "Доступ можно отозвать независимо от текущей роли и статуса получателя."));
    card.append(button("Отозвать доступ", () => {
      if (!confirm(`Отозвать доступ у ${recipient.login}? Существующие оценки сохранятся.`)) return;
      mutate(scope, $("page-status"), async () => {
        await request(scope, `/api/me/stylist-access/${recipient.id}`, { method: "DELETE" });
        loadView();
      });
    }, "danger quiet"));
    return card;
  }

  async function loadGrantForm(scope) {
    const target = $("page-tools");
    const status = node("div", "status", "Загружаем стилистов…");
    target.replaceChildren(status);
    try {
      const users = await allPages(scope, "/api/admin/users");
      scope.check();
      const stylists = users.filter((user) => user.role === "STYLIST" && user.status === "ACTIVE");
      const form = node("form", "inline-form");
      const select = field(form, "Предоставить доступ стилисту", "stylist", { type: "select" });
      fillOptions(select, stylists, (user) => user.login);
      const save = submitButton(form, "Предоставить доступ");
      save.disabled = !stylists.length;
      target.replaceChildren(form, status);
      setStatus(status, stylists.length ? "" : "Нет активных стилистов.");
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        const id = select.value;
        if (!id) return;
        mutate(scope, status, async () => {
          await request(scope, `/api/me/stylist-access/${encodeURIComponent(id)}`, { method: "PUT" });
          loadView();
        });
      });
    } catch (error) {
      report(error, scope, status, () => loadGrantForm(scope));
    }
  }

  async function openAccount(id) {
    const scope = openDialog("Аккаунт");
    const status = $("dialog-status");
    setStatus(status, "Загружаем актуальную версию…");
    try {
      const { data: account } = await request(scope, `/api/admin/users/${id}`);
      scope.check();
      $("detail-title").textContent = account.login;
      const target = $("detail-content");
      target.append(node("p", "fine", `ID ${account.id} · Версия ${account.version}\nСоздан: ${date(account.createdAt)}\nИзменён: ${date(account.modifiedAt)}`));
      const form = node("form");
      const role = field(form, "Роль", "role", { type: "select" });
      fillOptions(role, Object.keys(ROLE_NAMES).map((id) => ({ id, name: ROLE_NAMES[id] })));
      role.value = account.role;
      const state = field(form, "Статус", "status", { type: "select" });
      fillOptions(state, [{ id: "ACTIVE", name: "Активен" }, { id: "BLOCKED", name: "Заблокирован" }]);
      state.value = account.status;
      const self = account.id === scope.context.actorId;
      role.disabled = self;
      state.disabled = self;
      if (self) form.append(node("p", "notice", "Нельзя понизить свою роль или заблокировать собственный аккаунт."));
      const save = submitButton(form, "Сохранить роль и статус");
      save.disabled = self;
      target.append(form, button("Открыть коллекцию", () => navigate("wardrobe", { ...scope.context, kind: "admin", ownerId: account.id, ownerLogin: account.login })));
      setStatus(status);
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        if (self) return;
        const body = { role: role.value, status: state.value, version: account.version };
        if (!confirm(`Изменить роль и статус аккаунта ${account.login}?`)) return;
        mutate(scope, status, async () => {
          await request(scope, `/api/admin/users/${id}`, { method: "PUT", body });
          closeDialog(false);
          loadView();
        });
      });
    } catch (error) {
      report(error, scope, status, () => openAccount(id));
    }
  }

  function photoInput(form, required = false) {
    const input = field(form, "Фотографии", "photos", { type: "file", required });
    input.accept = "image/jpeg,image/png";
    input.multiple = true;
    form.append(node("p", "fine", "До 5 фотографий на вещь. JPEG или PNG, каждая не более 10 МБ."));
    return input;
  }

  function validatePhotos(files, existing = 0) {
    if (files.length + existing > 5) throw new Error("На одну вещь можно загрузить не более 5 фотографий.");
    for (const file of files) {
      if (!["image/jpeg", "image/png"].includes(file.type)) throw new Error("Выберите файлы JPEG или PNG.");
      if (file.size > 10_000_000) throw new Error("Размер каждой фотографии должен быть не больше 10 МБ.");
    }
  }

  async function openItem(id = null) {
    const scope = openDialog(id ? "Вещь и фотографии" : "Добавить вещь");
    const status = $("dialog-status");
    const route = routes(scope.context);
    setStatus(status, "Загружаем…");
    try {
      const [item, categories] = await Promise.all([
        id ? request(scope, `${route.items}/${id}`).then((result) => result.data) : Promise.resolve(null),
        allPages(scope, "/api/wardrobe/categories"),
      ]);
      scope.check();
      const target = $("detail-content");
      if (item) $("detail-title").textContent = item.name;
      if (route.editItems) {
        const form = node("form");
        form.id = "item-form";
        field(form, "Название", "name", { value: item?.name || "", maxLength: 300 });
        const category = field(form, "Категория", "categoryId", { type: "select" });
        fillOptions(category, categories);
        if (item) category.value = item.categoryId;
        field(form, "Цвет", "color", { value: item?.color || "", maxLength: 100 });
        field(form, "Материал", "material", { value: item?.material || "", maxLength: 100 });
        if (!item) photoInput(form);
        submitButton(form, "Сохранить вещь");
        target.append(form);
        form.addEventListener("submit", (event) => {
          event.preventDefault();
          mutate(scope, status, async () => {
            const payload = {
              name: requiredText(form, "name"),
              categoryId: Number(category.value),
              color: requiredText(form, "color"),
              material: requiredText(form, "material"),
            };
            if (!categories.some((entry) => entry.id === payload.categoryId)) throw new Error("Выберите категорию.");
            let body = item ? { ...payload, version: item.version } : payload;
            if (!item) {
              const files = [...form.elements.photos.files];
              validatePhotos(files);
              if (files.length) {
                body = new FormData();
                body.append("item", new Blob([JSON.stringify(payload)], { type: "application/json" }));
                files.forEach((file) => body.append("photos", file));
              }
            }
            const result = await request(scope, item ? `${route.items}/${item.id}` : route.items, { method: item ? "PUT" : "POST", body });
            await openItem(result.data.id);
            dialogDirty = true;
          });
        });
      } else {
        const category = categories.find((entry) => entry.id === item.categoryId);
        target.append(node("p", "badge", category?.name || `Категория #${item.categoryId}`));
        target.append(node("p", "", `${item.color} · ${item.material}`));
      }
      if (item) {
        const gallerySection = node("section", "detail-section");
        gallerySection.append(node("h3", "", "Фотографии"));
        target.append(gallerySection);
        gallery(scope, item, gallerySection, true);
        if (route.editItems) {
          target.append(button("Удалить вещь", () => {
            if (!confirm(`Удалить вещь «${item.name}»? Если она используется в образе, сервер отклонит удаление.`)) return;
            mutate(scope, status, async () => {
              await request(scope, `${route.items}/${item.id}?version=${item.version}`, { method: "DELETE" });
              closeDialog(false);
              loadView();
            });
          }, "danger quiet"));
        }
      }
      setStatus(status);
    } catch (error) {
      report(error, scope, status, () => openItem(id));
    }
  }

  function gallery(parentScope, item, target, management = false) {
    let scope = null;
    const content = node("div");
    target.append(content);
    async function load() {
      scope?.dispose();
      scope = parentScope.child();
      const current = scope;
      content.replaceChildren();
      const status = node("div", "status", "Загружаем фотографии…");
      status.setAttribute("role", "status");
      content.append(status);
      const route = routes(current.context);
      try {
        const photos = await photoData(current, item.id);
        current.check();
        const grid = node("div", "photo-gallery");
        content.append(grid);
        for (const photo of photos) {
          const box = node("div", "photo");
          const image = node("div", "photo-image", "Загрузка фото…");
          box.append(image, node("p", "fine", `${Math.ceil(photo.sizeBytes / 1000)} КБ · ${date(photo.createdAt)}`));
          grid.append(box);
          const loadImage = () => photoImage(current, item, photo, image).catch((error) => {
            if (current.alive && error.name !== "AbortError") {
              image.replaceChildren(node("p", "fine", "Фото недоступно"), button("Повторить", loadImage));
            }
          });
          loadImage();
          if (management && route.deletePhotos) {
            box.append(button("Удалить фото", () => {
              if (!confirm(`Удалить фотографию вещи «${item.name}»?`)) return;
              mutate(current, status, async () => {
                await request(current, `${route.items}/${item.id}/photos/${photo.id}`, { method: "DELETE" });
                dialogDirty = true;
                load();
              });
            }, "danger quiet"));
          }
        }
        setStatus(status, photos.length ? "" : "Пока нет фотографий.");
        if (management && route.editItems) {
          const form = node("form");
          form.className = "photo-upload";
          const input = photoInput(form, true);
          const save = submitButton(form, "Загрузить фото");
          save.disabled = photos.length >= 5;
          if (photos.length >= 5) form.append(node("p", "fine", "Достигнут предел: 5 фотографий. Удалите фото, чтобы добавить новое."));
          content.append(form);
          form.addEventListener("submit", (event) => {
            event.preventDefault();
            mutate(current, status, async () => {
              const files = [...input.files];
              if (!files.length) throw new Error("Выберите фотографии.");
              validatePhotos(files, photos.length);
              const body = new FormData();
              files.forEach((file) => body.append("photos", file));
              await request(current, `${route.items}/${item.id}/photos`, { method: "POST", body });
              dialogDirty = true;
              load();
            }, "Загружаем фотографии…");
          });
        }
      } catch (error) {
        report(error, current, status, load);
      }
    }
    load();
  }

  async function openOutfitForm() {
    const scope = openDialog("Создать образ");
    const status = $("dialog-status");
    const route = routes(scope.context);
    setStatus(status, "Загружаем параметры…");
    try {
      const precipitation = await allPages(scope, "/api/weather/precipitation-types");
      scope.check();
      const form = node("form");
      form.id = "outfit-form";
      field(form, "Название", "name", { maxLength: 300 });
      const mode = field(form, "Способ создания", "mode", { type: "select" });
      const modes = [{ id: "manual", name: "Составить вручную" }];
      if (route.ai) modes.push({ id: "ai", name: "Подобрать с AI" });
      fillOptions(mode, modes);
      const weather = node("div", "form-grid");
      field(weather, "Температура, °C", "temperatureC", { type: "number", value: "15" });
      field(weather, "Ветер, м/с", "windSpeedMps", { type: "number", value: "0", min: 0 });
      form.append(weather);
      const precipitationSelect = field(form, "Осадки", "precipitationTypeId", { type: "select" });
      fillOptions(precipitationSelect, precipitation);
      const choices = node("fieldset");
      choices.append(node("legend", "", "Вещи для образа"));
      const hint = node("p", "fine");
      const count = node("p", "selection-count");
      const selection = node("div", "item-picker");
      const pagination = node("div", "pagination");
      const pickerStatus = node("div", "status");
      choices.append(hint, count, selection, pickerStatus, pagination);
      form.append(choices);
      const aiNotice = node("p", "notice", "AI передаёт фотографии OpenAI. После исключения вещей без фото нужны 1–20 кандидатов. Без выбора используется весь гардероб. Доступ зависит от настройки сервера (по умолчанию аккаунт user). Запрос может занять до 60 секунд. Автоматического повтора или перехода на ручной подбор нет.");
      form.append(aiNotice);
      const save = submitButton(form, "Сохранить образ");
      $("detail-content").append(form);
      const selected = new Map();
      let pickerScope = null;
      function updateSelection() {
        const ai = mode.value === "ai";
        aiNotice.hidden = !ai;
        hint.textContent = ai ? "Выберите кандидатов или оставьте выбор пустым для всего гардероба." : "Выберите от 1 до 50 вещей. Выбор сохраняется между страницами.";
        count.textContent = selected.size ? `Выбрано: ${selected.size} · ${[...selected.values()].join(", ")}` : "Вещи не выбраны";
        save.textContent = ai ? "Подобрать с AI и сохранить" : "Сохранить образ";
      }
      async function loadPicker(page = 0) {
        pickerScope?.dispose();
        pickerScope = scope.child();
        const current = pickerScope;
        selection.replaceChildren();
        pagination.replaceChildren();
        setStatus(pickerStatus, "Загружаем вещи…");
        try {
          const result = await request(current, pageUrl(route.items, page));
          current.check();
          for (const item of result.data) {
            const label = node("label", "item-choice");
            const checkbox = node("input");
            checkbox.type = "checkbox";
            checkbox.checked = selected.has(item.id);
            checkbox.addEventListener("change", () => {
              if (busy) return;
              if (checkbox.checked) selected.set(item.id, item.name);
              else selected.delete(item.id);
              updateSelection();
            });
            label.append(checkbox, node("span", "", `${item.name} · ${item.color}`));
            selection.append(label);
          }
          pager(pagination, result, page, loadPicker);
          setStatus(pickerStatus, result.data.length ? "" : "На этой странице нет вещей.");
        } catch (error) {
          report(error, current, pickerStatus, () => loadPicker(page));
        }
      }
      mode.addEventListener("change", updateSelection);
      updateSelection();
      loadPicker();
      setStatus(status);
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        const ai = mode.value === "ai";
        mutate(scope, status, async () => {
          const itemIds = [...selected.keys()];
          if (!ai && (itemIds.length < 1 || itemIds.length > 50)) throw new Error("Выберите от 1 до 50 различных вещей.");
          const weather = {
            temperatureC: Number(form.elements.temperatureC.value),
            windSpeedMps: Number(form.elements.windSpeedMps.value),
            precipitationTypeId: Number(precipitationSelect.value),
          };
          if (!Number.isFinite(weather.temperatureC) || !Number.isFinite(weather.windSpeedMps) || weather.windSpeedMps < 0) throw new Error("Укажите температуру и неотрицательную скорость ветра.");
          if (!precipitation.some((entry) => entry.id === weather.precipitationTypeId)) throw new Error("Выберите осадки.");
          const body = { name: requiredText(form, "name"), weather };
          if (ai && itemIds.length) body.candidateItemIds = itemIds;
          if (!ai) body.itemIds = itemIds;
          await request(scope, ai ? "/api/outfits/ai" : route.outfits, { method: "POST", body });
          navigate("outfits", scope.context);
        }, ai ? "AI подбирает и сохраняет образ… Ожидание может занять до 60 секунд." : "Сохраняем образ…");
      });
    } catch (error) {
      report(error, scope, status, openOutfitForm);
    }
  }

  function voteName(vote) {
    return vote === "LIKE" ? "Нравится" : "Не нравится";
  }

  async function openOutfit(id) {
    const scope = openDialog("Образ");
    const status = $("dialog-status");
    const route = routes(scope.context);
    setStatus(status, "Загружаем образ…");
    try {
      const [result, precipitation] = await Promise.all([
        request(scope, `${route.outfits}/${id}`),
        allPages(scope, "/api/weather/precipitation-types"),
      ]);
      scope.check();
      const outfit = result.data;
      const target = $("detail-content");
      $("detail-title").textContent = outfit.name;
      const precipitationName = precipitation.find((entry) => entry.id === outfit.weather.precipitationTypeId)?.name || `Осадки #${outfit.weather.precipitationTypeId}`;
      target.append(node("p", "badge", SOURCE_NAMES[outfit.source] || outfit.source));
      target.append(node("p", "", `${weatherText(outfit.weather)} · ${precipitationName}`));
      target.append(node("p", "fine", `Автор #${outfit.authorId} · ${date(outfit.createdAt)}`));
      target.append(node("p", "rating-summary", `Нравится: ${outfit.likes} · Не нравится: ${outfit.dislikes}`));
      if (route.rate) renderRating(scope, outfit, target);
      const composition = node("section", "detail-section");
      composition.append(node("h3", "", "Состав образа"));
      target.append(composition);
      for (const itemId of outfit.itemIds) {
        const itemSection = node("section", "composition-item");
        composition.append(itemSection);
        const loadItem = async () => {
          itemSection.replaceChildren(node("p", "fine", "Загружаем вещь…"));
          try {
            const item = await itemData(scope, itemId);
            scope.check();
            itemSection.replaceChildren(node("h3", "", item.name), node("p", "muted", `${item.color} · ${item.material}`));
            gallery(scope, item, itemSection);
          } catch (error) {
            if (scope.alive && error.name !== "AbortError") {
              itemSection.replaceChildren(node("p", "fine", `Вещь #${itemId} недоступна`), button("Повторить", () => {
                scope.items.delete(itemId);
                loadItem();
              }));
            }
          }
        };
        loadItem();
      }
      if (route.history) renderHistory(scope, outfit.id, target);
      if (route.deleteOutfits) {
        target.append(button("Удалить образ", () => {
          if (!confirm(`Удалить образ «${outfit.name}»?`)) return;
          mutate(scope, status, async () => {
            await request(scope, `${route.outfits}/${id}`, { method: "DELETE" });
            closeDialog(false);
            loadView();
          });
        }, "danger quiet"));
      }
      setStatus(status);
    } catch (error) {
      report(error, scope, status, () => openOutfit(id));
    }
  }

  function renderRating(scope, outfit, target) {
    if (outfit.authorId === scope.context.actorId) {
      target.append(node("p", "notice", "Это созданный вами образ. Собственные образы оценивать нельзя."));
      return;
    }
    const actions = node("div", "actions rating-actions");
    const rating = outfit.myRating;
    const path = `${routes(scope.context).outfits}/${outfit.id}/rating`;
    const status = $("dialog-status");
    function vote(value) {
      mutate(scope, status, async () => {
        await request(scope, path, {
          method: rating ? "PUT" : "POST",
          body: rating ? { vote: value, version: rating.version } : { vote: value },
        });
        // Re-read totals and the server's version, including after a cancelled vote.
        await openOutfit(outfit.id);
        dialogDirty = true;
      });
    }
    for (const [value, title] of [["LIKE", "Нравится"], ["DISLIKE", "Не нравится"]]) {
      const control = button(title, () => vote(value), rating?.vote === value ? "secondary" : "quiet");
      control.setAttribute("aria-pressed", String(rating?.vote === value));
      control.disabled = rating?.vote === value;
      actions.append(control);
    }
    if (rating) actions.append(button("Отменить оценку", () => {
      mutate(scope, status, async () => {
        await request(scope, `${path}?version=${rating.version}`, { method: "DELETE" });
        await openOutfit(outfit.id);
        dialogDirty = true;
      });
    }));
    target.append(actions);
  }

  function renderHistory(parentScope, outfitId, target) {
    const section = node("section", "detail-section");
    section.append(node("h3", "", "История оценок"));
    section.append(node("p", "fine", "Все стилисты. Текущее состояние отмечено отдельно; архив не указывает причину изменения."));
    const content = node("div");
    section.append(content);
    target.append(section);
    let scope = null;
    async function load(page = 0) {
      scope?.dispose();
      scope = parentScope.child();
      const current = scope;
      const status = node("div", "status", "Загружаем историю…");
      content.replaceChildren(status);
      try {
        const path = `${routes(current.context).outfits}/${outfitId}/ratings/history`;
        const result = await request(current, pageUrl(path, page));
        current.check();
        const rows = node("div", "history-list");
        for (const entry of result.data) {
          const row = node("article", "history-row");
          row.append(node("strong", "", `Стилист #${entry.stylistId} · ${voteName(entry.vote)}`));
          row.append(node("span", "badge", entry.archivedAt === null ? "Текущее состояние" : "Архив"));
          row.append(node("p", "fine", `Версия ${entry.version} · Изменено: ${date(entry.modifiedAt)}${entry.archivedAt === null ? "" : ` · Архивировано: ${date(entry.archivedAt)}`}`));
          rows.append(row);
        }
        const pagination = node("div", "pagination");
        content.append(rows, pagination);
        pager(pagination, result, page, load);
        setStatus(status, result.data.length ? "" : "Пока нет оценок.");
      } catch (error) {
        report(error, current, status, () => load(page));
      }
    }
    load();
  }

  loadAccounts();
})();
