"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const PAGE_SIZE = 12;
  let session = { userId: null, controller: new AbortController() };
  let user = null;
  let tab = "wardrobe";
  let page = 0;
  let listRevision = 0;
  let dialogRevision = 0;
  let photoRevision = 0;
  let pickerRevision = 0;
  let categories = [];
  let precipitation = [];
  let accounts = [];
  const itemCache = new Map();
  const photoUrls = new Set();
  const selected = new Map();
  let editingItem = null;
  let photos = [];
  let photosReady = false;
  let pickerPage = 0;
  let dialogBusy = false;

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function status(id, message = "", error = false) {
    $(id).textContent = message;
    $(id).classList.toggle("error", error);
  }

  function context(inDialog = false) {
    return { session, dialog: inDialog ? dialogRevision : null };
  }

  function current(ctx) {
    return (
      ctx.session === session &&
      !ctx.session.controller.signal.aborted &&
      (ctx.dialog === null || ctx.dialog === dialogRevision)
    );
  }

  function check(ctx) {
    if (!current(ctx)) throw new DOMException("Запрос отменён", "AbortError");
  }

  function errorMessage(code, purpose) {
    const messages = {
      400:
        purpose === "ai"
          ? "Проверьте погоду и выбор вещей: AI нужны 1–20 вещей с фотографиями."
          : "Проверьте заполнение полей и выбранные значения.",
      401: "Сервер отклонил запрос. Повторите выбор пользователя.",
      403: "Недостаточно прав для этого действия.",
      404: "Объект больше не найден. Обновите список.",
      409:
        purpose === "delete-item"
          ? "Вещь изменена или используется в образе. Обновите список; сначала удалите связанные образы."
          : "Данные изменились или объект уже удалён. Обновите список и откройте форму заново.",
      413: "Файл слишком большой. Каждая фотография должна быть не более 10 МБ.",
      415: "Поддерживаются только фотографии JPEG и PNG.",
      422: "AI не смог подобрать подходящий образ. Измените набор вещей или условия.",
      502: "AI вернул некорректный ответ. Образ не удалось получить.",
      503:
        purpose === "ai"
          ? "AI недоступен: проверьте подключение ChatGPT, лимит и аккаунт user."
          : "Сервис или хранилище временно недоступны.",
      504: "Время ожидания AI истекло. Проверьте список образов перед новой попыткой.",
    };
    return messages[code] || `Не удалось выполнить запрос (HTTP ${code}).`;
  }

  async function api(path, ctx, options = {}) {
    check(ctx);
    const { purpose, blob, ...request } = options;
    const headers = new Headers(request.headers);
    if (ctx.session.userId !== null) headers.set("X-User-Id", String(ctx.session.userId));
    if (request.body && !(request.body instanceof FormData))
      headers.set("Content-Type", "application/json");
    let response;
    try {
      response = await fetch(path, {
        ...request,
        headers,
        signal: ctx.session.controller.signal,
        credentials: "same-origin",
        cache: "no-store",
      });
    } catch (error) {
      check(ctx);
      if (error.name === "AbortError") throw error;
      throw new Error(
        "Нет связи с сервером. Проверьте подключение. Если вы сохраняли данные, обновите список перед повтором.",
      );
    }
    check(ctx);
    if (!response.ok) {
      // Domain errors may have an empty or non-JSON body; their status is sufficient.
      throw new Error(errorMessage(response.status, purpose));
    }
    let data = null;
    if (response.status !== 204) {
      if (blob) data = await response.blob();
      else {
        const text = await response.text();
        if (text) {
          try {
            data = JSON.parse(text);
          } catch {
            throw new Error("Сервер вернул неожиданный ответ.");
          }
        }
      }
    }
    check(ctx);
    return { data, total: response.headers.get("X-Total-Count") };
  }

  function report(error, target, ctx) {
    if (current(ctx) && error.name !== "AbortError") status(target, error.message, true);
  }

  async function action(button, target, task, inDialog = false) {
    if (button.disabled || (inDialog && dialogBusy)) return;
    const ctx = context(inDialog);
    button.disabled = true;
    if (inDialog) {
      dialogBusy = true;
      document.querySelectorAll("[data-close]").forEach((control) => {
        control.disabled = true;
      });
    }
    status(target);
    try {
      await task(ctx);
    } catch (error) {
      report(error, target, ctx);
    } finally {
      if (current(ctx)) {
        button.disabled = false;
        if (inDialog) {
          dialogBusy = false;
          document.querySelectorAll("[data-close]").forEach((control) => {
            control.disabled = false;
          });
        }
      }
    }
  }

  function revokePhotos() {
    photoRevision++;
    photoUrls.forEach((url) => URL.revokeObjectURL(url));
    photoUrls.clear();
    $("photo-gallery").replaceChildren();
  }

  function clearDialog() {
    dialogBusy = false;
    document.querySelectorAll("[data-close]").forEach((control) => {
      control.disabled = false;
    });
    dialogRevision++;
    pickerRevision++;
    revokePhotos();
    editingItem = null;
    photos = [];
    photosReady = false;
    selected.clear();
    ["item-form", "photo-form", "outfit-form"].forEach((id) => {
      $(id).reset();
      $(id)
        .querySelectorAll("button, input, select, fieldset")
        .forEach((control) => {
          control.disabled = false;
        });
    });
    ["item-status", "photo-status", "outfit-status"].forEach((id) => status(id));
    ["item-picker", "picker-pagination", "selection-count"].forEach((id) =>
      $(id).replaceChildren(),
    );
  }

  function closeDialogs() {
    document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
    clearDialog();
  }

  function resetSession(message = "") {
    session.controller.abort();
    session = { userId: null, controller: new AbortController() };
    user = null;
    accounts = [];
    categories = [];
    precipitation = [];
    itemCache.clear();
    listRevision++;
    closeDialogs();
    $("user-picker-form").reset();
    $("user-picker-form").querySelector("button").disabled = false;
    $("refresh").disabled = false;
    ["cards", "pagination", "account"].forEach((id) => $(id).replaceChildren());
    $("user-picker").replaceChildren();
    $("user-picker").disabled = true;
    document
      .querySelectorAll("select[name=categoryId], select[name=precipitationTypeId]")
      .forEach((select) => select.replaceChildren());
    status("page-status");
    status("user-picker-status", message, Boolean(message));
    $("app").hidden = true;
    $("user-selection").hidden = false;
    $("user-picker").focus();
  }

  function renderAccounts(accounts) {
    const picker = $("user-picker");
    picker.replaceChildren();
    accounts.forEach((account) => {
      const option = node("option", "", `${account.login} · ${account.role}`);
      option.value = account.id;
      option.disabled = account.status !== "ACTIVE";
      picker.append(option);
    });
    const preferred =
      accounts.find((account) => account.login === "user" && account.status === "ACTIVE") ||
      accounts.find((account) => account.status === "ACTIVE");
    if (preferred) picker.value = String(preferred.id);
    picker.disabled = !preferred;
    $("user-picker-form").querySelector("button").disabled = !preferred;
  }

  async function loadAccounts() {
    const ctx = context();
    $("user-picker").disabled = true;
    $("user-picker-form").querySelector("button").disabled = true;
    status("user-picker-status", "Загружаем пользователей…");
    try {
      const loadedAccounts = await pagedEntries("/api/admin/users", ctx);
      check(ctx);
      accounts = loadedAccounts;
      renderAccounts(accounts);
      if (!accounts.length)
        status("user-picker-status", "На сервере нет аккаунтов для выбора.", true);
      else if (!accounts.some((account) => account.status === "ACTIVE"))
        status("user-picker-status", "Нет активных аккаунтов для выбора.", true);
      else status("user-picker-status");
    } catch (error) {
      report(error, "user-picker-status", ctx);
    }
  }

  function options(select, entries) {
    select.replaceChildren();
    entries.forEach((entry) => {
      const option = node("option", "", entry.name);
      option.value = entry.id;
      select.append(option);
    });
  }

  async function pagedEntries(path, ctx) {
    const entries = [];
    for (let index = 0; ; index++) {
      const { data } = await api(`${path}?page=${index}&size=50`, ctx);
      entries.push(...data);
      if (data.length < 50) return entries;
    }
  }

  async function refresh(ctx) {
    status("page-status", "Загружаем коллекцию…");
    $("create").disabled = true;
    const lists = await Promise.all([
      pagedEntries("/api/wardrobe/categories", ctx),
      pagedEntries("/api/weather/precipitation-types", ctx),
    ]);
    check(ctx);
    [categories, precipitation] = lists;
    $("create").disabled = false;
    await loadList(ctx);
  }

  $("user-picker-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    action(form.querySelector("button"), "user-picker-status", async (ctx) => {
      const account = accounts.find((entry) => String(entry.id) === form.elements.userId.value);
      if (!account || account.status !== "ACTIVE")
        throw new Error("Выберите активного пользователя.");
      ctx.session.userId = account.id;
      user = account;
      $("account").textContent = `${user.login} · ${user.role}`;
      $("user-selection").hidden = true;
      $("app").hidden = false;
      setTab("wardrobe");
      try {
        await refresh(ctx);
      } catch (error) {
        report(error, "page-status", ctx);
      }
    });
  });

  $("change-user").addEventListener("click", () => {
    resetSession();
    loadAccounts();
  });
  $("refresh").addEventListener("click", () => action($("refresh"), "page-status", refresh));

  function setTab(next) {
    tab = next;
    page = 0;
    listRevision++;
    const wardrobe = tab === "wardrobe";
    $("page-title").textContent = wardrobe ? "Мой гардероб" : "Мои образы";
    $("page-subtitle").textContent = wardrobe
      ? "Любимые вещи, готовые к новым сочетаниям."
      : "Готовые сочетания для вашей погоды.";
    $("create").textContent = wardrobe ? "+ Добавить вещь" : "+ Создать образ";
    ["wardrobe", "outfits"].forEach((name) => {
      if (name === next) $(`${name}-tab`).setAttribute("aria-current", "page");
      else $(`${name}-tab`).removeAttribute("aria-current");
    });
    $("cards").replaceChildren();
    $("pagination").replaceChildren();
  }

  ["wardrobe", "outfits"].forEach((name) =>
    $(`${name}-tab`).addEventListener("click", () => {
      setTab(name);
      const ctx = context();
      loadList(ctx).catch((error) => report(error, "page-status", ctx));
    }),
  );

  function pager(target, index, hasNext, onChange) {
    target.replaceChildren();
    const previous = node("button", "quiet", "← Назад");
    previous.type = "button";
    previous.disabled = index === 0;
    previous.addEventListener("click", () => onChange(index - 1));
    const next = node("button", "quiet", "Далее →");
    next.type = "button";
    next.disabled = !hasNext;
    next.addEventListener("click", () => onChange(index + 1));
    target.append(previous, node("span", "", `Страница ${index + 1}`), next);
  }

  function categoryName(id) {
    return categories.find((entry) => entry.id === id)?.name || `Категория #${id}`;
  }
  function precipitationName(id) {
    return precipitation.find((entry) => entry.id === id)?.name || `Осадки #${id}`;
  }

  function button(text, className, handler) {
    const element = node("button", className, text);
    element.type = "button";
    element.addEventListener("click", () => handler(element));
    return element;
  }

  async function loadList(ctx) {
    const revision = ++listRevision;
    const activeTab = tab;
    const activePage = page;
    status("page-status", "Загружаем коллекцию…");
    $("cards").replaceChildren();
    $("pagination").replaceChildren();
    try {
      const path = activeTab === "wardrobe" ? "/api/wardrobe/items" : "/api/outfits";
      const { data, total } = await api(`${path}?page=${activePage}&size=${PAGE_SIZE}`, ctx);
      if (activeTab === "wardrobe") data.forEach((item) => itemCache.set(item.id, item));
      else {
        const ids = [...new Set(data.flatMap((outfit) => outfit.itemIds))].filter(
          (id) => !itemCache.has(id),
        );
        await Promise.all(
          ids.map(async (id) => {
            try {
              const item = await api(`/api/wardrobe/items/${id}`, ctx);
              itemCache.set(id, item.data);
            } catch (error) {
              if (!current(ctx) || error.name === "AbortError") throw error;
            }
          }),
        );
      }
      check(ctx);
      if (revision !== listRevision) return;
      if (!data.length) {
        const empty = node("section", "empty");
        empty.append(
          node(
            "h2",
            "",
            activePage
              ? "На этой странице пусто"
              : activeTab === "wardrobe"
                ? "Место для любимых вещей"
                : "Ваш первый образ впереди",
          ),
          node(
            "p",
            "muted",
            activePage
              ? "Вернитесь на предыдущую страницу."
              : activeTab === "wardrobe"
                ? "Добавьте вещь и фотографию, чтобы начать собирать свою коллекцию."
                : "Выберите вещи и погоду — создайте сочетание сами или доверьте подбор AI.",
          ),
        );
        $("cards").append(empty);
      } else
        data.forEach((entry) =>
          $("cards").append(activeTab === "wardrobe" ? itemCard(entry) : outfitCard(entry)),
        );
      const hasNext =
        total === null ? data.length === PAGE_SIZE : (activePage + 1) * PAGE_SIZE < Number(total);
      pager($("pagination"), activePage, hasNext, (next) => {
        page = next;
        const nextCtx = context();
        loadList(nextCtx).catch((error) => report(error, "page-status", nextCtx));
      });
      status("page-status");
    } catch (error) {
      if (revision === listRevision) report(error, "page-status", ctx);
    }
  }

  function itemCard(item) {
    const card = node("article", "card");
    card.append(
      node("div", "item-mark", "↟"),
      node("span", "badge", categoryName(item.categoryId)),
      node("h3", "", item.name),
      node("p", "muted", `${item.color} · ${item.material}`),
    );
    const actions = node("div", "actions");
    actions.append(
      button("Изменить / фото", "", () => openItem(item)),
      button("Удалить", "danger quiet", (control) => {
        if (!confirm(`Удалить вещь «${item.name}»?`)) return;
        action(control, "page-status", async (ctx) => {
          await api(`/api/wardrobe/items/${item.id}?version=${item.version}`, ctx, {
            method: "DELETE",
            purpose: "delete-item",
          });
          itemCache.delete(item.id);
          await loadList(ctx);
        });
      }),
    );
    card.append(actions);
    return card;
  }

  function outfitCard(outfit) {
    const card = node("article", "card");
    const sources = { USER: "Вручную", AI: "Подобрано AI", STYLIST: "От стилиста" };
    card.append(
      node("span", "badge", sources[outfit.source] || outfit.source),
      node("h3", "", outfit.name),
      node(
        "p",
        "muted",
        `${outfit.weather.temperatureC} °C · ${precipitationName(outfit.weather.precipitationTypeId)} · ветер ${outfit.weather.windSpeedMps} м/с`,
      ),
    );
    const composition = node("ul", "composition");
    outfit.itemIds.forEach((id) =>
      composition.append(
        node("li", "", itemCache.get(id)?.name || `Вещь #${id} (название недоступно)`),
      ),
    );
    card.append(
      composition,
      node("p", "muted", `Нравится: ${outfit.likes} · Не нравится: ${outfit.dislikes}`),
    );
    const actions = node("div", "actions");
    actions.append(
      button("Удалить образ", "danger quiet", (control) => {
        if (!confirm(`Удалить образ «${outfit.name}»?`)) return;
        action(control, "page-status", async (ctx) => {
          await api(`/api/outfits/${outfit.id}`, ctx, { method: "DELETE" });
          await loadList(ctx);
        });
      }),
    );
    card.append(actions);
    return card;
  }

  document.querySelectorAll("[data-close]").forEach((control) =>
    control.addEventListener("click", () => {
      if (!dialogBusy) closeDialogs();
    }),
  );
  document.querySelectorAll("dialog").forEach((dialog) =>
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      if (!dialogBusy) closeDialogs();
    }),
  );
  $("create").addEventListener("click", () => (tab === "wardrobe" ? openItem() : openOutfit()));

  function openItem(item = null) {
    closeDialogs();
    editingItem = item;
    const form = $("item-form");
    options(form.elements.categoryId, categories);
    $("item-title").textContent = item ? "Вещь и фотографии" : "Добавить вещь";
    $("initial-photos").hidden = Boolean(item);
    $("photo-section").hidden = !item;
    if (item)
      ["name", "categoryId", "color", "material"].forEach((key) => {
        form.elements[key].value = item[key];
      });
    $("item-dialog").showModal();
    if (item) {
      const ctx = context(true);
      loadPhotos(item.id, ctx).catch((error) => report(error, "photo-status", ctx));
    }
  }

  function textValue(form, name) {
    const value = form.elements[name].value.trim();
    if (!value) throw new Error("Заполните все обязательные поля: одни пробелы не подходят.");
    return value;
  }

  function validatePhotos(files, existing = 0) {
    if (files.length + existing > 5) throw new Error("У вещи может быть не более 5 фотографий.");
    files.forEach((file) => {
      if (!["image/jpeg", "image/png"].includes(file.type))
        throw new Error("Выберите фотографии JPEG или PNG.");
      if (file.size > 10_000_000) throw new Error("Каждая фотография должна быть не более 10 МБ.");
    });
  }

  $("item-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    action(
      form.querySelector("button[type=submit]"),
      "item-status",
      async (ctx) => {
        const item = {
          name: textValue(form, "name"),
          categoryId: Number(form.elements.categoryId.value),
          color: textValue(form, "color"),
          material: textValue(form, "material"),
        };
        if (!categories.some((entry) => entry.id === item.categoryId))
          throw new Error("Выберите категорию. Если список пуст, обновите страницу коллекции.");
        let body;
        let method = "POST";
        let path = "/api/wardrobe/items";
        if (editingItem) {
          method = "PUT";
          path += `/${editingItem.id}`;
          body = JSON.stringify({ ...item, version: editingItem.version });
        } else {
          const files = [...form.elements.photos.files];
          validatePhotos(files);
          if (files.length) {
            body = new FormData();
            body.append("item", new Blob([JSON.stringify(item)], { type: "application/json" }));
            files.forEach((file) => body.append("photos", file));
          } else body = JSON.stringify(item);
        }
        status("item-status", "Сохраняем вещь…");
        const { data } = await api(path, ctx, { method, body });
        itemCache.set(data.id, data);
        closeDialogs();
        await loadList(context());
      },
      true,
    );
  });

  async function loadPhotos(itemId, ctx) {
    revokePhotos();
    const revision = photoRevision;
    photosReady = false;
    $("photo-form").querySelector("button").disabled = true;
    status("photo-status", "Загружаем фотографии…");
    const { data } = await api(`/api/wardrobe/items/${itemId}/photos`, ctx);
    if (revision !== photoRevision) return;
    photos = data;
    photosReady = true;
    $("photo-form").querySelector("button").disabled = false;
    status("photo-status", photos.length ? "" : "Пока нет фотографий.");
    const loads = photos.map(async (photo, index) => {
      const box = node("div", "photo");
      const placeholder = node("p", "fine", "Загрузка фото…");
      const remove = button("Удалить фото", "danger quiet", (control) => {
        if (!confirm("Удалить эту фотографию?")) return;
        action(
          control,
          "photo-status",
          async (removeCtx) => {
            await api(`/api/wardrobe/items/${itemId}/photos/${photo.id}`, removeCtx, {
              method: "DELETE",
            });
            await loadPhotos(itemId, removeCtx);
          },
          true,
        );
      });
      box.append(placeholder, remove);
      $("photo-gallery").append(box);
      try {
        const { data: image } = await api(
          `/api/wardrobe/items/${itemId}/photos/${photo.id}/content`,
          ctx,
          { blob: true },
        );
        if (revision !== photoRevision) return;
        const url = URL.createObjectURL(image);
        photoUrls.add(url);
        const img = node("img");
        img.alt = `Фото ${index + 1}: ${editingItem.name}`;
        img.addEventListener(
          "error",
          () => {
            URL.revokeObjectURL(url);
            photoUrls.delete(url);
            img.replaceWith(node("p", "fine", "Фото недоступно"));
          },
          { once: true },
        );
        img.src = url;
        placeholder.replaceWith(img);
      } catch (error) {
        if (current(ctx) && revision === photoRevision && error.name !== "AbortError")
          placeholder.textContent = "Фото недоступно";
      }
    });
    await Promise.all(loads);
  }

  $("photo-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    action(
      form.querySelector("button"),
      "photo-status",
      async (ctx) => {
        if (!photosReady) throw new Error("Сначала дождитесь загрузки списка фотографий.");
        const files = [...form.elements.photos.files];
        validatePhotos(files, photos.length);
        const body = new FormData();
        files.forEach((file) => body.append("photos", file));
        status("photo-status", "Загружаем фотографии…");
        await api(`/api/wardrobe/items/${editingItem.id}/photos`, ctx, { method: "POST", body });
        form.reset();
        await loadPhotos(editingItem.id, ctx);
      },
      true,
    );
  });

  function selectionHint() {
    const isAI = $("outfit-form").elements.mode.value === "ai";
    $("selection-hint").textContent = isAI
      ? "Выберите кандидатов или оставьте выбор пустым для всего гардероба."
      : "Выберите от 1 до 50 вещей. Выбор сохраняется при переходе между страницами.";
    $("selection-count").textContent = selected.size
      ? `Выбрано: ${selected.size} · ${[...selected.values()].join(", ")}`
      : "Вещи не выбраны";
    $("ai-hint").hidden = !isAI;
    $("outfit-form").querySelector("button[type=submit]").textContent = isAI
      ? "Подобрать с AI и сохранить"
      : "Сохранить образ";
  }

  function openOutfit() {
    closeDialogs();
    options($("outfit-form").elements.precipitationTypeId, precipitation);
    pickerPage = 0;
    selectionHint();
    $("outfit-dialog").showModal();
    const ctx = context(true);
    loadPicker(ctx).catch((error) => report(error, "outfit-status", ctx));
  }

  async function loadPicker(ctx) {
    const revision = ++pickerRevision;
    const index = pickerPage;
    $("item-picker").replaceChildren(node("p", "fine", "Загружаем вещи…"));
    $("picker-pagination").replaceChildren();
    const { data } = await api(`/api/wardrobe/items?page=${index}&size=${PAGE_SIZE}`, ctx);
    if (revision !== pickerRevision) return;
    $("item-picker").replaceChildren();
    if (!data.length) $("item-picker").append(node("p", "fine", "На этой странице нет вещей."));
    data.forEach((item) => {
      itemCache.set(item.id, item);
      const label = node("label", "item-choice");
      const input = node("input");
      input.type = "checkbox";
      input.checked = selected.has(item.id);
      input.addEventListener("change", () => {
        if (input.checked) selected.set(item.id, item.name);
        else selected.delete(item.id);
        selectionHint();
      });
      label.append(input, node("span", "", `${item.name} · ${item.color}`));
      $("item-picker").append(label);
    });
    pager($("picker-pagination"), index, data.length === PAGE_SIZE, (next) => {
      pickerPage = next;
      loadPicker(ctx).catch((error) => report(error, "outfit-status", ctx));
    });
  }

  $("outfit-form").elements.mode.addEventListener("change", selectionHint);
  $("outfit-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    action(
      form.querySelector("button[type=submit]"),
      "outfit-status",
      async (ctx) => {
        const isAI = form.elements.mode.value === "ai";
        const itemIds = [...selected.keys()];
        if (!isAI && (!itemIds.length || itemIds.length > 50))
          throw new Error("Выберите от 1 до 50 вещей.");
        const weather = {
          temperatureC: Number(form.elements.temperatureC.value),
          precipitationTypeId: Number(form.elements.precipitationTypeId.value),
          windSpeedMps: Number(form.elements.windSpeedMps.value),
        };
        if (!precipitation.some((entry) => entry.id === weather.precipitationTypeId))
          throw new Error("Выберите осадки. Если список пуст, обновите страницу коллекции.");
        const body = { name: textValue(form, "name"), weather };
        if (isAI) {
          if (itemIds.length) body.candidateItemIds = itemIds;
        } else body.itemIds = itemIds;
        status(
          "outfit-status",
          isAI
            ? "AI подбирает и сохраняет образ… Это может занять до 60 секунд. Дождитесь ответа; повторный запрос не нужен."
            : "Сохраняем образ…",
        );
        form.querySelectorAll("input, select, fieldset").forEach((control) => {
          control.disabled = true;
        });
        try {
          await api(isAI ? "/api/outfits/ai" : "/api/outfits", ctx, {
            method: "POST",
            purpose: isAI ? "ai" : "outfit",
            body: JSON.stringify(body),
          });
          closeDialogs();
          setTab("outfits");
          await loadList(context());
        } finally {
          if (current(ctx))
            form.querySelectorAll("input, select, fieldset").forEach((control) => {
              control.disabled = false;
            });
        }
      },
      true,
    );
  });

  loadAccounts();
})();
